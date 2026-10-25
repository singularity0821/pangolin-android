package net.pangolin.Pangolin.util

/**
 * Validation and formatting for the user-entered upstream DNS server addresses.
 *
 * Parsed by hand rather than with InetAddress.getByName (which falls back to a
 * DNS lookup for anything that isn't a literal) or InetAddresses.isNumericAddress
 * (API 29+, minSdk is 24).
 */
object DnsServerAddress {
    /** Whether [address] is a literal IPv4 or IPv6 address. */
    fun isValid(address: String): Boolean = isIPv4(address) || isIPv6(address)

    /**
     * Formats [address] as the "host:port" olm expects, wrapping IPv6 addresses
     * in brackets ("[2606:4700:4700::1111]:53") so the port stays unambiguous.
     */
    fun toHostPort(address: String, port: Int = 53): String =
        if (address.contains(':')) "[$address]:$port" else "$address:$port"

    private fun isIPv4(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            // No leading zeros - olm's Go parser rejects them as ambiguous with octal
            part.length in 1..3 &&
                part.all { it in '0'..'9' } &&
                (part.length == 1 || part[0] != '0') &&
                part.toInt() <= 255
        }
    }

    private fun isIPv6(address: String): Boolean {
        val compression = address.indexOf("::")
        if (compression != address.lastIndexOf("::")) return false // at most one "::"

        val groups = if (compression >= 0) {
            splitGroups(address.substring(0, compression)) + splitGroups(address.substring(compression + 2))
        } else {
            address.split(':')
        }

        var count = 0
        for ((index, group) in groups.withIndex()) {
            if (index == groups.lastIndex && group.contains('.')) {
                // Embedded IPv4 tail, e.g. ::ffff:192.0.2.1, takes the place of two groups
                // and has to end the address (so not "1.2.3.4::")
                if (address.endsWith("::") || !isIPv4(group)) return false
                count += 2
            } else {
                if (group.length !in 1..4 || !group.all { it.isHexDigit() }) return false
                count++
            }
        }

        // "::" stands in for at least one group of zeros
        return if (compression >= 0) count <= 7 else count == 8
    }

    private fun splitGroups(part: String): List<String> =
        if (part.isEmpty()) emptyList() else part.split(':')

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
