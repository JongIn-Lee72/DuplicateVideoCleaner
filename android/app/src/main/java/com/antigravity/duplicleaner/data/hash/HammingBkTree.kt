package com.antigravity.duplicleaner.data.hash

/**
 * BK-Tree(Burkhard-Keller Tree) 근접 이웃 탐색 결과 항목.
 */
data class BkMatch<T>(
    val item: T,
    val hash: Long,
    val distance: Int
)

/**
 * 64-bit 지각 해시(dHash / pHash) 공간에서 해밍 거리(Hamming Distance, 0..64) 삼각 부등식을 활용해
 * O(N^2) 전수 비교 대신 평균 O(N log N) 시간 복잡도로 유사 미디어 후보를 탐색하는 Burkhard-Keller 메트릭 트리.
 */
class HammingBkTree<T> {

    private class Node<T>(
        val hash: Long,
        firstItem: T
    ) {
        val items: MutableList<T> = mutableListOf(firstItem)
        val children: HashMap<Int, Node<T>> = HashMap()
    }

    private var root: Node<T>? = null

    var size: Int = 0
        private set

    /**
     * 64-bit 해시와 연결된 항목을 BK-Tree에 삽입합니다.
     * 동일한 64-bit 해시(해밍 거리 0)를 가진 여러 항목도 유실 없이 노드의 리스트에 함께 보관됩니다.
     */
    fun insert(hash: Long, item: T) {
        size++
        val currentRoot = root
        if (currentRoot == null) {
            root = Node(hash, item)
            return
        }

        var current: Node<T> = currentRoot
        while (true) {
            val d = VideoPerceptualHasher.hammingDistance(hash, current.hash)
            if (d == 0) {
                current.items.add(item)
                return
            }
            val next = current.children[d]
            if (next == null) {
                current.children[d] = Node(hash, item)
                return
            }
            current = next
        }
    }

    /**
     * 삼각 부등식(|d(q, n) - d(n, c)| <= d(q, c))에 기반해
     * queryHash와의 해밍 거리가 maxDistance 이하인 모든 항목을 탐색하여 반환합니다.
     */
    fun searchWithinDistance(queryHash: Long, maxDistance: Int): List<BkMatch<T>> {
        if (maxDistance < 0) return emptyList()
        val currentRoot = root ?: return emptyList()

        val matches = mutableListOf<BkMatch<T>>()
        val stack = ArrayDeque<Node<T>>()
        stack.addLast(currentRoot)

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val d = VideoPerceptualHasher.hammingDistance(queryHash, node.hash)
            if (d <= maxDistance) {
                for (item in node.items) {
                    matches.add(BkMatch(item = item, hash = node.hash, distance = d))
                }
            }

            val minEdge = maxOf(1, d - maxDistance)
            val maxEdge = minOf(64, d + maxDistance)
            for (edgeDist in minEdge..maxEdge) {
                val child = node.children[edgeDist]
                if (child != null) {
                    stack.addLast(child)
                }
            }
        }

        matches.sortBy { it.distance }
        return matches
    }

    fun clear() {
        root = null
        size = 0
    }
}
