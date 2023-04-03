# DESIGN A KEY-VALUE STORE

A key-value store, 
- also referred to as a key-value database, 
- is a non-relational database. 
- Each unique identifier is stored as a key with its associated value. 
- This data pairing is known as a “key-value” pair.

For performance reasons, 
- a short key works better.

Keys can be plain text or hashed values. The value in a key-value pair can be strings, lists, objects, etc.

# Understand the problem and establish design scope:
Design a key-value store that comprises of the following characteristics:
- The size of a key-value pair is small: less than 10 KB.
- Ability to store big data.
- High availability: The system responds quickly, even during failures.
- High scalability: The system can be scaled to support large data set.
- Automatic scaling: The addition/deletion of servers should be automatic based on traffic.
- Tunable consistency.
- Low latency.

# Single server key-value store
- Developing a key-value store that resides in a single server is easy. 
- An intuitive approach is to store key-value pairs in a hash table, which keeps everything in memory. 
- Even though memory access is fast, fitting everything in memory may be impossible due to the space constraint. 
- Two optimizations can be done to fit more data in a single server:
  - Data compression
  - Store only frequently used data in memory and the rest on disk 
- Even with these optimizations, a single server can reach its capacity very quickly. 
- A distributed key value store is required to support big data.

# CAP theorem
CAP theorem states it is impossible for a distributed system to simultaneously provide more than two of these three guarantees: **consistency**, **availability**, and **partition tolerance**. Let us establish a few definitions.
- Consistency: consistency means all clients see the samedata at the same time no matter which node they connect to.
- Availability: availability means any client which requests data gets a response even if some of the nodes are down. 
- Partition Tolerance: a partition indicates a communication break between two nodes. Partition tolerance means the system continues to operate despite network partitions. 

CAP theorem states that one of the three properties must be sacrificed to support 2 of the 3 properties.

Some popular key-value store systems are Dynamo, Cassandra & BigTable.

# System components
- Data partition
- Data replication
- Consistency
- Inconsistency resolution
- Handling failures
- System architecture diagram
- Write path
- Read path

# Data partition
- For large applications, it is infeasible to fit the complete data set in a single server. 
- The simplest way to accomplish this is to split the data into smaller partitions and store them in multiple servers. 
- There are two challenges while partition in the data:
  - Distribute data across multiple servers evenly.
  - Minimize data movement when nodes are added or removed.
- Consistent hashing is a great technique to solve these problems.

# Data replication
- To achieve high availability and reliability, data must be replicated asynchronously over N servers, where N is a configurable parameter. 
- These N servers are chosen using a logic: 
  - after a key is mapped to a position on the hash ring, 
  - walk clockwise from that position and 
  - choose the first N servers on the ring to store data copies.
- With virtual nodes, the first N nodes on the ring may be owned by fewer than N physical servers. 
- To avoid this issue, we only choose unique servers while performing the clockwise walk logic.
- Nodes in the same data center often fail at the same time due to power outages, network issues, natural disasters, etc. 
- For better reliability, replicas are placed in distinct data centers, and data centers are connected through high-speed networks.

# Consistency
- Since data is replicated at multiple nodes, it must be synchronized across replicas. 
- Quorum consensus can guarantee consistency for both read and write operations. 
- Let us establish a few definitions first.
- N = The number of replicas
- W = A write quorum of size W. For a write operation to be considered as successful, write operation must be acknowledged from W replicas.
- R = A read quorum of size R. For a read operation to be considered as successful, read operation must wait for responses from at least R replicas

Consider an exmaple where, 
- there are 10 nodes/servers, N = 3, W = 1. 
- This means data is replicated at s0, s1, and s2. 
- W = 1 means that the coordinator must receive at least one acknowledgment before the write operation is considered as successful. 
- For instance, if we get an acknowledgment from s1, we no longer need to wait for acknowledgements from s0 and s2. 
- A coordinator acts as a proxy between the client and the nodes.

The configuration of W, R and N is a typical tradeoff between latency and consistency. 
- If W = 1 or R = 1, an operation is returned quickly because a coordinator only needs to wait for a response from any of the replicas. 
- If W or R > 1, the system offers better consistency; however, the query will be slower because the coordinator must wait for the response from the slowest replica.
- If W + R > N, strong consistency is guaranteed because there must be at least one overlapping node that has the latest data to ensure consistency.

How to configure N, W, and R to fit our use cases? Here are some of the possible setups: 
- If R = 1 and W = N, the system is optimized for a fast read.
- If W = 1 and R = N, the system is optimized for fast write.
- If W + R > N, strong consistency is guaranteed (Usually N = 3, W = R = 2).
- If W + R <= N, strong consistency is not guaranteed.

# Consistency models
- Consistency model is other important factor to consider when designing a key-value store. 
- A consistency model defines the degree of data consistency, and a wide spectrum of possible consistency models exist:
  - Strong consistency: any read operation returns a value corresponding to the result of the most updated write data item. A client never sees out-of-date data.
  - Weak consistency: subsequent read operations may not see the most updated value.
  - Eventual consistency: this is a specific form of weak consistency. Given enough time, all updates are propagated, and all replicas are consistent.

- Strong consistency is usually achieved by forcing a replica not to accept new reads/writes until every replica has agreed on current write. This approach is not ideal for highly available systems because it could block new operations. 
- Dynamo and Cassandra adopt eventual consistency, which is our recommended consistency model for our key-value store. From concurrent writes, eventual consistency allows inconsistent values to enter the system and force the client to read the values to reconcile.
