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
