# Consistent Hashing

To achieve horizontal scaling, it is important to distribute requests/data efficiently and evenly across servers. 
**Consistent hashing** is a commonly used technique to achieve this goal.

### Quoted from Wikipedia: 
- Consistent hashing is a special kind of hashing 
- such that when a hash table is re-sized and consistent hashing is used, 
- only `k/n` keys need to be remapped on average, where `k` is the **number of keys**, and `n` is the **number of slots**. 
- In contrast, in most traditional hash tables, a change in the number of array slots **causes nearly all keys to be remapped**.

### Hash space and hash ring:
- Assume `SHA-1` is used as the hash function `f`, 
- and the output range of the hash function is: `x0, x1, x2, x3, …, xn`. 
- In cryptography, SHA-1’s **Hash space** goes from `0 to 2^160 - 1`. 
- That means `x0` corresponds to `0`, `xn` corresponds to `2^160 – 1`, 
- and all the other hash values in the middle fall between `0 and 2^160 - 1`.
![HLD](/images/HashSpace.png)
- By connecting both ends, we get a **Hash ring**
<img src="https://github.com/PS00161093/system-design/blob/main/images/HashRing.png" width="400">

### Hash servers:
- Using the same hash function `f`, we map servers based on **server IP** or **name** onto the ring.
![HLD](/images/HashServer.png)

- Mapping of Servers & Keys on the HashRing based on their hash value generated from function `f`.
![Mapping](/images/HashKeyServerMapping.jpg)

### Server Lookup:
- To determine which server a key is stored on, 
- We go clockwise from the key position on the ring until a server is found.
- Going clockwise, 
- `key0` is stored on `server 0`
- `key1` is stored on `server 1`
- `key2` is stored on `server 2`
- `key3` is stored on `server 3`
![ServerLookUp](/images/ServerLookUp.png)

### Add a server
- Using the logic described above, adding a new server will only require redistribution of a fraction of keys.
- After a new `server 4` is added, only `key0` needs to be redistributed. 
- `k1, k2, and k3` remain on the same servers. 
- Before `server 4` is added, `key0` is stored on `server 0`. 
- Now, `key0` will be stored on `server 4` because `server 4` is the first server it encounters by going clockwise from `key0’s` position on the ring. 
- The other keys are not redistributed based on consistent hashing algorithm.
![NewServer](/images/NewServer.png)

### Remove a server
- When a server is removed, 
- Only a small fraction of keys require redistribution with consistent hashing. 
- When `server 1` is removed, only `key1` must be remapped to `server 2`. 
- The rest of the keys are unaffected.

### Issue with the above basic approach
The basic steps of the basic approach are: 
- Map servers and keys on to the ring using a uniformly distributed hash function.
- To find out which server a key is mapped to, go clockwise from the key position until the first server on the ring is found.

Two problems are identified with this approach.
1. **It is impossible to keep the same size of partitions on the ring for all servers considering a server can be added or removed.**
  - A partition is the hash space between adjacent servers. 
  - It is possible that the size of the partitions on the ring assigned to each server is very small or fairly large.
  - For example, if `s1` is removed, `s2’s` partition is twice as large as `s0` and `s3’s` partition.
![Issue1](/images/BasicApproachIssue1.png)

2. **It is possible to have a non-uniform key distribution on the ring.**
  - For instance, if servers are mapped to positions listed in below figure, most of the keys are stored on `server 2`. 
  - However, `server 1` and `server 3` have no data.
![BasicApproachIssue2](/images/BasicApproachIssue2.png)

A technique called **virtual nodes** or replicas is used to solve these problems.

### Virtual Nodes
- A virtual node refers to the real node, and 
- Each server is represented by multiple virtual nodes on the ring.
- In below figure, both `server 0` and `server 1` have 3 virtual nodes. 
- The 3 is arbitrarily chosen; and in real-world systems, the number of virtual nodes is much larger. 
- Instead of using `s0`, we have `s0_0`, `s0_1`, and `s0_2` to represent `server 0` on the ring. 
- Similarly, `s1_0`, `s1_1`, and `s1_2` represent `server 1` on the ring. 
- With virtual nodes, each server is responsible for multiple partitions. 
- Partitions (edges) with label `s0` are managed by `server 0`. 
- On the other hand, partitions with label `s1` are managed by `server 1`.
![virtuaNodes](/images/VirtualNodes.png)

### Find key on Virtual Node
- To find which server a key is stored on, we go clockwise from the key’s location and find the first virtual node encountered on the ring.
- As the number of virtual nodes increases, 
  - the distribution of keys becomes more balanced. 
  - This is because the standard deviation gets smaller with more virtual nodes, leading to balanced data distribution.
- However, more spaces are needed to store data about virtual nodes. 
- This is a tradeoff, and we can tune the number of virtual nodes to fit our system requirements.

### Find affected keys
- When a server is added or removed, a fraction of data needs to be redistributed.
- In below figure, `server 4` is added onto the ring. 
- The affected range starts from `s4` (newly added node) and moves **anticlockwise** around the ring until a server is found (`s3`). 
- Thus, keys located between `s3` and `s4` need to be redistributed to `s4`.
![AffectedKeys](/images/AffectedKeys.png)
- When a server is removed, the affected range starts from removed server and moves anticlockwise around the ring until a server is found. 
- Thus, keys located between removed server and immediate server found in counterclockwise direction must be redistributed to the next immediate server in the clockwise direction.

### Real-world systems usage of Consistent Hashing
- [Partitioning component of Amazon’s Dynamo database](https://www.allthingsdistributed.com/files/amazon-dynamososp2007.pdf)
- [Data partitioning across the cluster in Apache Cassandra](http://www.cs.cornell.edu/Projects/ladis2009/papers/Lakshman-ladis2009.PDF)
- Discord chat application.
- [Akamai content delivery network](http://theory.stanford.edu/~tim/s16/l/l1.pdf)
- [Maglev network load balancer](https://static.googleusercontent.com/media/research.google.com/en//pubs/archive/44824.pdf)
