# How to test

## Prerequisites
You need to install:
 * jdk 11 or above
 * maven 3.6.3 or above


## how to run tests
Just run:  mvn clean test


# Candidate notes

## about DB guarantees expectations
I only use the DB as a semaphore to avoid processing two alerts (and acknowledgments) at the same time. In that case I write a single-column (should be PK) row in a table. If a try to insert a row and the PK is violated then means that the semaphore is closed and that alert/ack will be ignored. I'm assuming that the DB has some TTL mechanism that will automatically delete the semaphore rows after a time just to act as a circuit breaker in case of te JVM went down during alert processing and was not able to release the semaphore
