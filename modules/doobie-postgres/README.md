# Doobie Postgres

Implementation using only PostgreSQL as an infrastructure dependency. Relies on transactions 
[advisory locks](https://www.postgresql.org/docs/current/explicit-locking.html) for a pessimistic locking strategy.

### Customize

Default implementation provides a way to initialize the database as well as queries for this database. 
It can be customized by changing the name of table or the type of the columns but you can also initialize the 
structure on your side and provide an implementation of the `Queries` interface.