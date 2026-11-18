package app.unbraid.vpn

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The built-in NetBird client as the engine reports it (`Libbox.netbirdStatus()`). */
@Serializable
data class NbStatus(
    /** "off", "starting", "running" or "failed". */
    val state: String = "off",
    val error: String? = null,
    val ip: String? = null,
    val fqdn: String? = null,
    /** NetBird's own network, e.g. 100.101.0.0/16. */
    val network: String? = null,
    /** Connected to the management server. */
    val management: Boolean = false,
    val peers: List<Peer> = emptyList(),
    @SerialName("exit_nodes") val exitNodes: List<ExitNode> = emptyList(),
    /** Networks behind routing peers that this device reaches. */
    val routes: List<String> = emptyList(),
) {
    @Serializable
    data class Peer(val fqdn: String = "", val ip: String = "", val connected: Boolean = false, val relayed: Boolean = false)

    /** An exit node by its network ID ([app.unbraid.data.Route.nbExit]) and its routing peer's name. */
    /** [online]: a routing peer that serves it is connected now (an idle one, with lazy connections, is not). */
    @Serializable
    data class ExitNode(val id: String, val name: String = "", val selected: Boolean = false, val online: Boolean = false) {
        val label get() = name.substringBefore('.').ifEmpty { id }
    }

    val running get() = state == "running"
    /** NetBird's network and its routed networks, for the engine's rules ([Compiler.Input.nbNetworks]). */
    val networks get() = (listOfNotNull(network) + routes).sorted()
    /**
     * DNS names of this device and its peers ([Compiler.Input.nbNames]): only those under this device's own NetBird
     * domain ("netbird.cloud", a self-hosted one), so a peer named after another site's name can't take its lookups.
     */
    val names get(): List<String> {
        val domain = fqdn?.trim('.')?.lowercase()?.substringAfter('.', "")?.takeIf { '.' in it } ?: return emptyList()
        return (listOfNotNull(fqdn) + peers.map { it.fqdn }).map { it.trim('.').lowercase() }.filter { it.endsWith(".$domain") }.distinct().sorted()
    }
    /** The DNS domain NetBird's names are under, as the device's own name shows it. */
    val domain get() = fqdn?.trim('.')?.substringAfter('.', "")?.takeIf { '.' in it }

    companion object {
        // The engine writes null for an empty list (a failed or starting client): read as the default, keeping the error.
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        /** A status the engine gave; anything unreadable counts as off. */
        fun parse(s: String?): NbStatus = runCatching { json.decodeFromString(serializer(), s!!) }.getOrDefault(NbStatus())
    }
}
