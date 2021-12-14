# cass-class
Generating Kotlin Data Classes from Cassandra Tables

# Usage

This generates the Kotlin sources from tables and UDTs in the specified database and keyspace and puts them in the subfolder `src`:
```
cass-class {keyspace} --dir src --package data.cql --address localhost --port 9042 --datacenter DC1
```
