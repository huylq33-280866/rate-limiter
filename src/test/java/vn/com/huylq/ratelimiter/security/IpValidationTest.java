package vn.com.huylq.ratelimiter.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.com.huylq.ratelimiter.test.fixtures.TestFixtures;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Security tests for IP address validation and X-Forwarded-For handling.
 *
 * Tests cover:
 * - X-Forwarded-For from trusted proxies
 * - X-Forwarded-For rejection from untrusted sources
 * - Direct IP fallback
 * - Multiple IP chain handling
 * - IP format validation
 * - CIDR range matching
 */
@DisplayName("IP Address Validation Security Tests")
public class IpValidationTest {

    private MockIpExtractor ipExtractor;

    @BeforeEach
    void setUp() {
        ipExtractor = new MockIpExtractor();
        // Configure trusted proxies
        ipExtractor.addTrustedProxy("10.20.30.40");      // Our load balancer
        ipExtractor.addTrustedProxy("10.0.0.0/8");        // Internal network
        ipExtractor.addTrustedProxy("172.16.0.0/12");     // Docker network
        ipExtractor.addTrustedProxy("192.168.0.0/16");    // Private network
    }

    // ==================== X-Forwarded-For Trust Tests ====================

    @Test
    @DisplayName("Should accept X-Forwarded-For from trusted proxy")
    void testAcceptsXForwardedForFromTrustedProxy() {
        // Given: Request from trusted proxy with X-Forwarded-For header
        String directIp = "10.20.30.40";  // Our load balancer
        String clientIp = TestFixtures.TEST_IP_ADDRESS;  // Real client
        String xForwardedFor = clientIp;

        // When: Extracting client IP
        String result = ipExtractor.extractClientIp(directIp, xForwardedFor);

        // Then: Should use X-Forwarded-For value from trusted proxy
        assertThat(result).isEqualTo(clientIp);
    }

    @Test
    @DisplayName("Should accept X-Forwarded-For from trusted CIDR range")
    void testAcceptsXForwardedForFromTrustedCidrRange() {
        // Given: Request from IP within trusted CIDR range
        String directIp = "10.5.5.5";           // Within 10.0.0.0/8
        String clientIp = TestFixtures.TEST_IP_ADDRESS;
        String xForwardedFor = clientIp;

        // When: Extracting client IP
        String result = ipExtractor.extractClientIp(directIp, xForwardedFor);

        // Then: Should accept X-Forwarded-For from trusted CIDR
        assertThat(result).isEqualTo(clientIp);
    }

    @Test
    @DisplayName("Should reject X-Forwarded-For from untrusted source")
    void testRejectsXForwardedForFromUntrustedSource() {
        // Given: Request from untrusted IP with X-Forwarded-For header
        String directIp = "203.0.113.1";        // Untrusted external IP
        String spoofedClientIp = "203.0.113.99"; // Fake client IP
        String xForwardedFor = spoofedClientIp;

        // When: Extracting client IP
        String result = ipExtractor.extractClientIp(directIp, xForwardedFor);

        // Then: Should use direct IP, ignore X-Forwarded-For
        assertThat(result).isEqualTo(directIp);
    }

    @Test
    @DisplayName("Should use direct IP when X-Forwarded-For header missing")
    void testUsesDirectIpWhenProxyHeaderMissing() {
        // Given: Request without X-Forwarded-For header
        String directIp = TestFixtures.TEST_IP_ADDRESS;
        String xForwardedFor = null;  // No header

        // When: Extracting client IP
        String result = ipExtractor.extractClientIp(directIp, xForwardedFor);

        // Then: Should use direct IP
        assertThat(result).isEqualTo(directIp);
    }

    // ==================== Multiple IP Chain Tests ====================

    @Test
    @DisplayName("Should extract first IP from X-Forwarded-For chain")
    void testHandlesMultipleIpChain() {
        // Given: X-Forwarded-For with multiple IPs (proxy chain)
        String directIp = "10.20.30.40";  // Trusted proxy
        String clientIp = "203.0.113.45";  // Real client
        String proxyIp = "203.0.113.100";  // Another proxy
        String xForwardedFor = clientIp + ", " + proxyIp;  // Client first, then other proxies

        // When: Extracting client IP
        String result = ipExtractor.extractClientIp(directIp, xForwardedFor);

        // Then: Should extract first IP (rightmost is most recent proxy)
        // Standard X-Forwarded-For: client, proxy1, proxy2, ... proxyN
        assertThat(result).isEqualTo(clientIp);
    }

    @Test
    @DisplayName("Should handle X-Forwarded-For with whitespace")
    void testHandlesXForwardedForWithWhitespace() {
        // Given: X-Forwarded-For with inconsistent whitespace
        String directIp = "10.20.30.40";  // Trusted
        String clientIp = "203.0.113.45";
        String xForwardedFor = "  " + clientIp + "  ,  203.0.113.100  ";

        // When: Extracting client IP
        String result = ipExtractor.extractClientIp(directIp, xForwardedFor);

        // Then: Should trim whitespace and extract correctly
        assertThat(result).isEqualTo(clientIp);
    }

    // ==================== IP Format Validation Tests ====================

    @Test
    @DisplayName("Should validate IPv4 address format")
    void testValidatesIpFormat() {
        // Given: Valid IPv4 addresses
        String[] validIps = {
            "192.168.1.1",
            "10.0.0.1",
            "255.255.255.255",
            "0.0.0.0",
            "203.0.113.45"
        };

        // When: Checking format
        for (String ip : validIps) {
            boolean isValid = ipExtractor.isValidIp(ip);

            // Then: Should be valid
            assertThat(isValid)
                .as("IP %s should be valid", ip)
                .isTrue();
        }
    }

    @Test
    @DisplayName("Should reject malformed IP addresses")
    void testRejectsMalformedIps() {
        // Given: Invalid IP addresses
        String[] invalidIps = {
            "256.256.256.256",      // Out of range
            "192.168.1",            // Missing octet
            "192.168.1.1.1",        // Too many octets
            "192.168.1.a",          // Non-numeric
            "not-an-ip",            // Text
            "",                     // Empty
            "192.168.-1.1",         // Negative
            "192.168.1 .1",         // Space
        };

        // When: Checking format
        for (String ip : invalidIps) {
            boolean isValid = ipExtractor.isValidIp(ip);

            // Then: Should be invalid
            assertThat(isValid)
                .as("IP '%s' should be invalid", ip)
                .isFalse();
        }
    }

    @Test
    @DisplayName("Should reject malicious IP patterns")
    void testRejectsMaliciousIpPatterns() {
        // Given: Malicious IP-like strings
        String[] maliciousPatterns = {
            "192.168.1.1; DROP TABLE users;",
            "192.168.1.1' OR '1'='1",
            "192.168.1.1\nSOME_COMMAND",
            "../../../etc/passwd",
            "<script>alert('xss')</script>"
        };

        // When: Checking format
        for (String pattern : maliciousPatterns) {
            boolean isValid = ipExtractor.isValidIp(pattern);

            // Then: Should be invalid
            assertThat(isValid)
                .as("Pattern '%s' should be rejected", pattern)
                .isFalse();
        }
    }

    // ==================== CIDR Range Matching Tests ====================

    @Test
    @DisplayName("Should match IP within CIDR range")
    void testCidrRangeMatching() {
        // Given: IPs and CIDR ranges
        String[] testCases = {
            // IP, CIDR, expectedMatch
        };

        // Standard CIDR ranges
        assertThat(ipExtractor.isIpInRange("10.5.5.5", "10.0.0.0/8")).isTrue();
        assertThat(ipExtractor.isIpInRange("10.255.255.255", "10.0.0.0/8")).isTrue();
        assertThat(ipExtractor.isIpInRange("11.0.0.1", "10.0.0.0/8")).isFalse();

        // /16 range
        assertThat(ipExtractor.isIpInRange("172.16.0.1", "172.16.0.0/12")).isTrue();
        assertThat(ipExtractor.isIpInRange("172.31.255.255", "172.16.0.0/12")).isTrue();
        assertThat(ipExtractor.isIpInRange("172.15.255.255", "172.16.0.0/12")).isFalse();

        // /24 range
        assertThat(ipExtractor.isIpInRange("192.168.1.1", "192.168.1.0/24")).isTrue();
        assertThat(ipExtractor.isIpInRange("192.168.1.255", "192.168.1.0/24")).isTrue();
        assertThat(ipExtractor.isIpInRange("192.168.2.1", "192.168.1.0/24")).isFalse();

        // /32 range (single IP)
        assertThat(ipExtractor.isIpInRange("203.0.113.45", "203.0.113.45/32")).isTrue();
        assertThat(ipExtractor.isIpInRange("203.0.113.46", "203.0.113.45/32")).isFalse();
    }

    // ==================== Security Edge Cases ====================

    @Test
    @DisplayName("Should handle localhost addresses correctly")
    void testLocalhostHandling() {
        // Given: Localhost addresses from development environment
        String[] localhostIps = {"127.0.0.1", "::1"};

        // When: Extracting from localhost
        // Then: Should treat as direct connection (not trusted proxy)
        String result = ipExtractor.extractClientIp("127.0.0.1", "203.0.113.45");

        // Localhost is not in trusted proxy list, so X-Forwarded-For ignored
        assertThat(result).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("Should prevent IP spoofing via X-Forwarded-For")
    void testIpSpoofingPrevention() {
        // Given: External attacker trying to spoof internal IP
        String directIp = "203.0.113.1";        // Attacker's real IP
        String spoofedIp = "10.0.0.1";          // Trying to appear as internal
        String xForwardedFor = spoofedIp;

        // When: Rate limiter extracts IP
        String result = ipExtractor.extractClientIp(directIp, xForwardedFor);

        // Then: Should use direct IP, preventing spoofing
        assertThat(result).isEqualTo(directIp);
        assertThat(result).isNotEqualTo(spoofedIp);
    }

    @Test
    @DisplayName("Should handle empty X-Forwarded-For header safely")
    void testEmptyXForwardedForHandling() {
        // Given: Empty X-Forwarded-For header from trusted proxy
        String directIp = "10.20.30.40";  // Trusted
        String xForwardedFor = "";        // Empty

        // When: Extracting IP
        String result = ipExtractor.extractClientIp(directIp, xForwardedFor);

        // Then: Should fall back to direct IP
        assertThat(result).isEqualTo(directIp);
    }

    // ==================== Mock Implementation ====================

    /**
     * Mock IP extractor for testing
     */
    static class MockIpExtractor {
        private final java.util.Set<String> trustedProxies = new java.util.HashSet<>();

        void addTrustedProxy(String proxyOrCidr) {
            trustedProxies.add(proxyOrCidr);
        }

        String extractClientIp(String directIp, String xForwardedFor) {
            // Only trust X-Forwarded-For from trusted proxies
            if (isTrustedProxy(directIp)) {
                if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
                    String clientIp = xForwardedFor.split(",")[0].trim();
                    if (isValidIp(clientIp)) {
                        return clientIp;
                    }
                }
            }

            return directIp;
        }

        private boolean isTrustedProxy(String ip) {
            // Check exact IP match
            if (trustedProxies.contains(ip)) {
                return true;
            }

            // Check CIDR ranges
            for (String proxy : trustedProxies) {
                if (proxy.contains("/")) {
                    if (isIpInRange(ip, proxy)) {
                        return true;
                    }
                }
            }

            return false;
        }

        boolean isValidIp(String ip) {
            if (ip == null || ip.isEmpty() || ip.trim().isEmpty()) {
                return false;
            }

            // Check for SQL injection, XSS, etc.
            if (ip.contains(";") || ip.contains("'") || ip.contains("\"") ||
                ip.contains("<") || ip.contains(">") || ip.contains("\\n") ||
                ip.contains("../") || ip.contains("<script>")) {
                return false;
            }

            String[] parts = ip.split("\\.");
            if (parts.length != 4) {
                return false;
            }

            try {
                for (String part : parts) {
                    int num = Integer.parseInt(part);
                    if (num < 0 || num > 255) {
                        return false;
                    }
                }
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        boolean isIpInRange(String ip, String cidr) {
            String[] parts = cidr.split("/");
            if (parts.length != 2) {
                return false;
            }

            String networkStr = parts[0];
            int prefixLength = Integer.parseInt(parts[1]);

            long ipLong = ipToLong(ip);
            long networkLong = ipToLong(networkStr);
            long mask = (0xFFFFFFFFl << (32 - prefixLength));

            return (ipLong & mask) == (networkLong & mask);
        }

        private long ipToLong(String ip) {
            String[] parts = ip.split("\\.");
            long result = 0;
            for (String part : parts) {
                result = result * 256 + Integer.parseInt(part);
            }
            return result;
        }
    }
}
