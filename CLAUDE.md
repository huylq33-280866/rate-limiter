# Rate Limiter

## Overview
Distributed rate limiter implementing 5 algorithms for System Design Interview practice
Algorithms: TokenBucket, LeakingBucket, FixedWindowCounter, SlidingWindowLog, SlidingWindowCounter

## Critical Rules
- Each algorithm must be independently testable - no shared mutable state
- Redis operations must be atomic - use Lua scripts for read-modify-write
- Fail open when Redis is unavailable - never block requests  on infrastructure failure
- Thread safety required - concurrent requests to same key must be consistent

## Architecture:
- Java 21, Spring Boot 4, Redis (Redisson), Hexagonal Architecture.
- Package root: `vn.com.huylq.ratelimiter`

## Gotchas
- Sliding window requires sorted sets in Redis - `ZRANGEBYSCORE` for cleanup