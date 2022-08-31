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


## 5. Algorithms for rate limiting
- Token bucket
- Leaking bucket
- Fixed window counter
- Sliding window log
- Sliding window counter

### **Token bucket algorithm**
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

### **Leaking bucket algorithm**
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

### **Fixed window counter algorithm**
- **Working Principle**
  - The algorithm divides the timeline into fix-sized time windows and assign a counter for each window.
  - Each request increments the counter by one.
  - Once the counter reaches the pre-defined threshold, new requests are dropped until a new time window starts.
- **Pros**
  - Easy to understand.
  - Resetting available quota at the end of a unit time window fits certain use cases.
- **Cons**
  - Spike in traffic at the edges of a window could cause more requests than the allowed quota to go through.
