package com.antigravity.duplicleaner.data.hash

/**
 * Disjoint-Set (Union-Find) data structure with Path Compression and Union-by-Size
 * over indices 0 until items.size.
 *
 * Designed for transitive similarity clustering while strictly bounding cluster growth
 * to prevent runaway transitive chaining drift (A ~ B ~ C ~ ... ~ Z).
 *
 * @param items The list of items to cluster.
 * @param maxClusterSize The maximum allowed elements in any single cluster (defaults to 24).
 */
class TransitiveClusterMerger<T>(
    val items: List<T>,
    val maxClusterSize: Int = 24
) {
    private val parent = IntArray(items.size) { it }
    private val componentSize = IntArray(items.size) { 1 }

    /**
     * Finds the root representative of the set containing [x] with path compression.
     * Returns -1 if [x] is out of bounds.
     */
    fun find(x: Int): Int {
        if (x !in items.indices) return -1
        var root = x
        while (root != parent[root]) {
            root = parent[root]
        }
        // Path compression
        var curr = x
        while (curr != root) {
            val next = parent[curr]
            parent[curr] = root
            curr = next
        }
        return root
    }

    /**
     * Unites the sets containing [indexA] and [indexB].
     *
     * @return true if the two disjoint sets were merged,
     *         false if indices are invalid, already in the same set, or if merging
     *         would exceed [maxClusterSize].
     */
    fun union(indexA: Int, indexB: Int): Boolean {
        if (indexA !in items.indices || indexB !in items.indices) return false

        val rootA = find(indexA)
        val rootB = find(indexB)
        if (rootA == rootB || rootA == -1 || rootB == -1) return false

        val combinedSize = componentSize[rootA] + componentSize[rootB]
        if (combinedSize > maxClusterSize) {
            return false // Guard against runaway transitive drift
        }

        // Union-by-size: merge smaller component under larger component
        if (componentSize[rootA] >= componentSize[rootB]) {
            parent[rootB] = rootA
            componentSize[rootA] = combinedSize
        } else {
            parent[rootA] = rootB
            componentSize[rootB] = combinedSize
        }
        return true
    }

    /**
     * Returns the size of the cluster containing [index], or 0 if [index] is invalid.
     */
    fun clusterSizeOf(index: Int): Int {
        val root = find(index)
        return if (root != -1) componentSize[root] else 0
    }

    /**
     * Groups [items] by their root representative and returns all clusters with size > 1.
     * Preserves item ordering within each cluster based on original list indices.
     */
    fun extractClusters(): List<List<T>> {
        if (items.isEmpty()) return emptyList()

        val groups = LinkedHashMap<Int, MutableList<T>>()
        for (i in items.indices) {
            val root = find(i)
            if (root != -1) {
                groups.getOrPut(root) { mutableListOf() }.add(items[i])
            }
        }
        return groups.values.filter { it.size > 1 }
    }

    /**
     * Groups item indices by their root representative and returns all cluster index lists with size > 1.
     */
    fun extractClusterIndices(): List<List<Int>> {
        if (items.isEmpty()) return emptyList()

        val groups = LinkedHashMap<Int, MutableList<Int>>()
        for (i in items.indices) {
            val root = find(i)
            if (root != -1) {
                groups.getOrPut(root) { mutableListOf() }.add(i)
            }
        }
        return groups.values.filter { it.size > 1 }
    }
}
