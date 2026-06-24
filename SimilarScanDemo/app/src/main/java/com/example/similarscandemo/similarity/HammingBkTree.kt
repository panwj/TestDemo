package com.example.similarscandemo.similarity

/**
 * 针对 64 位感知哈希的 BK-Tree。
 *
 * 树中只保存 assetId 和 Long hash，不保存 Bitmap、颜色数组或媒体元数据，因此十万级资源
 * 也能保持较低内存占用。查询使用汉明距离三角不等式，只返回可能满足最终阈值的资源，
 * 避免全量 O(n²) 比较，也不会像固定 hash 分段那样漏掉跨段差异。
 */
class HammingBkTree {
    private var root: Node? = null

    fun add(assetId: Long, hash: Long) {
        if (root == null) {
            root = Node(hash).apply { assetIds += assetId }
            return
        }

        var current: Node = requireNotNull(root)
        while (true) {
            val distance = hammingDistance(hash, current.hash)
            if (distance == 0) {
                current.assetIds += assetId
                return
            }
            val child = current.children[distance]
            if (child == null) {
                current.children[distance] = Node(hash).apply { assetIds += assetId }
                return
            }
            current = child
        }
    }

    fun query(hash: Long, maxDistance: Int): Set<Long> {
        val result = linkedSetOf<Long>()
        val rootNode = root ?: return result
        val pending = ArrayDeque<Node>()
        pending += rootNode

        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            val distance = hammingDistance(hash, node.hash)
            if (distance <= maxDistance) result += node.assetIds

            val minimum = (distance - maxDistance).coerceAtLeast(0)
            val maximum = distance + maxDistance
            node.children.forEach { (edge, child) ->
                if (edge in minimum..maximum) pending += child
            }
        }
        return result
    }

    private fun hammingDistance(first: Long, second: Long): Int {
        return java.lang.Long.bitCount(first xor second)
    }

    private class Node(val hash: Long) {
        val assetIds = mutableListOf<Long>()
        val children = mutableMapOf<Int, Node>()
    }
}
