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
