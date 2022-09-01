# System design for an API Rate Limiter

## 1. What is a Rate limiter?
In a network system, a rate limiter is used to control the rate of traffic sent by a client or a service. In the HTTP world, a rate limiter limits the number of client requests allowed to be sent over a specified period. If the API request count exceeds the threshold defined by the rate limiter, all the excess calls are blocked.

## 2. Benifits of Rate limiter
- Prevent resource starvation caused by Denial of Service (DoS) attack.
- Reduce cost.
- Prevent servers from being overloaded.

## 3. Common question to clarify the scope of design
- What kind of rate limiter are we going to design? Is it a client-side rate limiter or server-side API rate limiter?
- Does the rate limiter throttle API requests based on IP, the user ID, or other properties?
- What is the scale of the system? Is it built for a startup or a big company with a large user base?
- Will the system work in a distributed environment?
- Is the rate limiter a separate service or should it be implemented in application code?
- Do we need to inform users who are throttled?

## 4. Design Requirements
- Accurately limit excessive requests.
- Low latency. The rate limiter should not slow down HTTP response time.
- Use as little memory as possible.
- Distributed rate limiting. The rate limiter can be shared across multiple servers or processes.
- Exception handling. Show clear exceptions to users when their requests are throttled.
- High fault tolerance. If there are any problems with the rate limiter (for example, a cache server goes offline), it does not affect the entire system.

###
Client side Rate limiter is **NOT** preferred because:
- Client is an unreliable place to enforce rate limiting because client requests can easily be forged by malicious actors.
- We might not have control over the client implementation.

###
API Gateways have the capability of rate limiting. 
While designing a rate limiter, an important question to ask ourselves is: 
where should the rater limiter be implemented,on the server-side or in a gateway? 
There is no absolute answer. It depends on your company’s current technology stack, engineering resources, priorities, goals, etc. 
Here are a few general guidelines:
- Evaluate your current technology stack, such as programming language, cache service, etc.
- Make sure your current programming language is efficient to implement rate limiting on the server-side.
- Identify the rate limiting algorithm that fits your business needs. When you implement everything on the serverside, you have full control of the algorithm. However, your choice might be limited if you use a third-party gateway.
- If you have already used microservice architecture and included an API gateway in the design to perform authentication, IP whitelisting, etc., you may add a rate limiter to the API gateway.
- Building your own rate limiting service takes time. If you do not have enough engineering resources to implement a rate limiter, a commercial API gateway is a better option.


## 5. Popular algorithms for rate limiting
- Token bucket
- Leaking bucket
- Fixed window counter
- Sliding window log
- Sliding window counter

### `Token bucket algorithm`
- Simple, well understood & widely used.
- Commonly used by internet companies like Amazon, Stripe.
- **Working Principle**
  - A token bucket is a container that has pre-defined capacity. 
  - Tokens are put in the bucket at preset rates periodically. Once the bucket is full, no more tokens are added.
  - Each request consumes one token. When a request arrives, we check if there are enough tokens in the bucket.
  - If there are enough tokens, we take one token out for each request, and the request goes through.
  - If there are not enough tokens, the request is dropped.
  - The token bucket algorithm takes two parameters:
    - **Bucket size**: the maximum number of tokens allowed in the bucket
    - **Refill rate**: number of tokens put into the bucket every second
  - How many buckets do we need? This varies, and it depends on the rate-limiting rules.
  - It is usually necessary to have different buckets for different API endpoints.
  - For instance, if a user is allowed to make 1 post per second, add 150 friends per day, and like 5 posts per second, 3 buckets are required for each user.
  - If we need to throttle requests based on IP addresses, each IP address requires a bucket.
  - If the system allows a maximum of 10,000 requests per second, it makes sense to have a global bucket shared by all requests.
- **Pros**
  - Easy to implement.
  - Memory efficient.
  - Token bucket allows a burst of traffic for short periods. A request can go through as long as there are tokens left.
- **Cons**
  - Two parameters in the algorithm are bucket size and token refill rate. However, it might be challenging to tune them properly

### `Leaking bucket algorithm`
- Similar to the token bucket except that requests are processed at a fixed rate.
- Usually implemented with a first-in-first-out (FIFO) queue.
- Shopify uses this alogorithm.
- **Working Principle**
  - When a request arrives, the system checks if the queue is full.
  - If it is not full, the request is added to the queue.
  - Otherwise, the request is dropped.
  - Requests are pulled from the queue and processed at regular intervals.
  - This algorithm takes 2 parameters:
    - **Bucket size**: it is equal to the queue size. The queue holds the requests to be processed at a fixed rate.
    - **Outflow rate:** it defines how many requests can be processed at a fixed rate, usually in seconds.
- **Pros**
  - Memory efficient given the limited queue size.
  - Requests are processed at a fixed rate therefore it is suitable for use cases that a stable outflow rate is needed.
- **Cons**
  - A burst of traffic fills up the queue with old requests, and if they are not processed in time, recent requests will be rate limited.
  - There are two parameters in the algorithm. It might not be easy to tune them properly.

### `Fixed window counter algorithm`
- **Working Principle**
  - The algorithm divides the timeline into fix-sized time windows and assign a counter for each window.
  - Each request increments the counter by one.
  - Once the counter reaches the pre-defined threshold, new requests are dropped until a new time window starts.
- **Pros**
  - Easy to understand.
  - Resetting available quota at the end of a unit time window fits certain use cases.
- **Cons**
  - Spike in traffic at the edges of a window could cause more requests than the allowed quota to go through.

### `Sliding window log algorithm`
- Fixes the issues found in Fixed window counter algorithm.
- **Working Principle**
  - The algorithm keeps track of request timestamps. Timestamp data is usually kept in cache, such as sorted sets of Redis.
  - When a new request comes in, remove all the outdated timestamps. Outdated timestamps are defined as those older than the start of the current time window.
  - Add timestamp of the new request to the log.
  - If the log size is the same or lower than the allowed count, a request is accepted. Otherwise, it is rejected.
  - The log is empty when a new request arrives at 1:00:01. Thus, the request is allowed.
  - A new request arrives at 1:00:30, the timestamp 1:00:30 is inserted into the log. After the insertion, the log size is 2, not larger than the allowed count. Thus, the request is allowed.
  - A new request arrives at 1:00:50, and the timestamp is inserted into the log. After the insertion, the log size is 3, larger than the allowed size 2. Therefore, this request is rejected even though the timestamp remains in the log.
  - A new request arrives at 1:01:40. Requests in the range [1:00:40,1:01:40] are within the latest time frame, butrequests sent before 1:00:40 are outdated. Two outdated timestamps, 1:00:01 and 1:00:30, are removed from the log. After the remove operation, the log size becomes 2; therefore, the request is accepted.
- **Pros**
  - Rate limiting implemented by this algorithm is very accurate. In any rolling window, requests will not exceed the rate limit.
- **Cons**
  - The algorithm consumes a lot of memory because even if a request is rejected, its timestamp might still be stored in memory.

### `Sliding window counter algorithm`
- A hybrid approach that combines the fixed window counter and sliding window log.
- **Working Principle**
  - Assume the rate limiter allows a maximum of 7 requests per minute.
  - There are 5 requests in the previous minute and 3 in the current minute.
  - For a new request that arrives at a 30% position in the current minute, the number of requests in the rolling window is calculated using the following formula: 
    - **Requests in current window + requests in the previous window * overlap percentage of the rolling window and previous window**
    - Using this formula, we get `3 + 5 * 0.7% = 6.5` request.
  - Depending on the use case, the number can either be rounded up or down.
  - In our example, it is rounded down to 6.
  - Since the rate limiter allows a maximum of 7 requests per minute, the current request can go through. However, the limit will be reached after receiving one more request.
- **Pros**
  - It smooths out spikes in traffic because the rate is based on the average rate of the previous window. 
  - Memory efficient.
- **Cons**
  - It only works for not-so-strict look back window.
  - It is an approximation of the actual rate because it assumes requests in the previous window are evenly distributed.

## 6. Where shall we store counters?
- Using the database is not a good idea due to slowness of disk access. 
- In-memory cache is chosen because it is fast and supports time-based expiration strategy. 
- For instance, Redis is a popular option to implement rate limiting. 
- It is an in-memory store that offers two commands:
 - INCR: It increases the stored counter by 1.
 - EXPIRE: It sets a timeout for the counter. 
- If the timeout expires, the counter is automatically deleted.
- The client sends a request to rate limiting middleware.
- Rate limiting middleware fetches the counter from the corresponding bucket in Redis and checks if the limit is reached or not.
- If the limit is reached, the request is rejected.
- If the limit is not reached, the request is sent to API servers.
- Meanwhile, the system increments the counter and saves it back to Redis.

## 7. Rate limiting rules
Rules are generally written in configuration files and saved on disk.

## 8. Exceeding the rate limit
In case a request is rate limited, APIs return a HTTP response code 429 (too many requests) to the client. Depending on the use cases, we may enqueue the ratelimited requests to be processed later. For example, if some orders are rate limited due to system overload, we may keep those orders to be processed later.

## 9. Rate limiter headers
- How does a client know whether it is being throttled? And how does a client know the number of allowed remaining requests before being throttled? 
- **The answer lies in HTTP response headers.**
- The rate limiter returns the following HTTP headers to clients:
  - `X-Ratelimit-Remaining`: The remaining number of allowed requests within the window.
  - `X-Ratelimit-Limit`: It indicates how many calls the client can make per time window.
  - `X-Ratelimit-Retry-After`: The number of seconds to wait until you can make a request again without being throttled. When a user has sent too many requests, a 429 too many requests error and X-Ratelimit-Retry-After header are returned to the client.

## 10. Request flow:
- Rules are stored on the disk. Workers frequently pull rules from the disk and store them in the cache.
- When a client sends a request to the server, the request is sent to the rate limiter middleware first.
- Rate limiter middleware loads rules from the cache.
- It fetches counters and last request timestamp from Redis cache.
- Based on the response, the rate limiter decides, if the request is not rate limited, it is forwarded to API servers.
- if the request is rate limited, the rate limiter returns 429 too many requests error to the client.
- In the meantime, the request is either dropped or forwarded to the queue.

## 11. Rate limiter in a distributed environment
- Building a rate limiter that works in a single server environment is not difficult. However, scaling the system to support multiple servers and concurrent threads is a different story. There are two challenges:
  - Race condition
  - Synchronization issue

### **Race condition**
- If two requests concurrently read the counter value before either of them writes the value back, each will increment the counter by one and write it back without checking the other thread. Both requests (threads) believe they have the correct counter value.
- Locks are the most obvious solution for solving race condition. However, locks will significantly slow down the system.
- Two strategies are commonly used to solve the problem:
  - Lua script
  - Sorted sets data structure in Redis

### **Synchronization issue**
- To support millions of users, one rate limiter server might not be enough to handle the traffic.
- When multiple rate limiter servers are used, synchronization is required.
- As the web tier is stateless, clients can send requests to a different rate limiter.
- If no synchronization happens, rate limiter 1 does not contain any data about client 2. Thus, the rate limiter cannot work properly.
- One possible solution is to use sticky sessions that allow a client to send traffic to the same rate limiter.
- This solution is not advisable because it is neither scalable nor flexible.
- A better approach is to use centralized data stores like Redis.
- 
