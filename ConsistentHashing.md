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
