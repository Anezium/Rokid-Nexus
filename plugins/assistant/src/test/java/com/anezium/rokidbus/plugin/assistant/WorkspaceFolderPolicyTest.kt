package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFolderPolicyTest {
    @Test
    fun `system storage needs no provider roots query`() {
        assertTrue(workspaceFolderIsLocal(WorkspaceStoreTest.TREE, "primary:Documents",
            readRoots = { error("No roots query on the fast path") }, isChild = { error("No child query") }))
    }

    @Test
    fun `a third-party root must declare the local-only flag`() {
        assertTrue(local("local", listOf(root("local", 2))))
        assertFalse(local("local", listOf(root("local", null))))
        assertFalse(local("local", listOf(root("local", 1))))
        assertFalse(local("local", listOf(root("local", -1))))
    }

    @Test
    fun `a provider with a local root cannot authorize its cloud root or cloud descendants`() {
        val roots = listOf(root("local", 2), root("cloud", 0))
        assertFalse(local("cloud", roots, setOf("local")))
        assertFalse(local("cloud-child", roots, emptySet()))
        assertTrue(local("local-child", roots, setOf("local")))
    }

    @Test
    fun `a complete declaration of exclusively local roots also covers subfolders`() {
        assertTrue(workspaceFolderIsLocal(TREE, "nested", readRoots = { listOf(root("local", 2), root("sd", 2)) },
            isChild = { error("No parent query when every root declares local content") }))
    }

    @Test
    fun `ambiguous roots and bounded enumeration failures are rejected`() {
        assertFalse(local("unknown", emptyList()))
        assertFalse(local("local", listOf(root("local", 2), root("local", 0))))
        assertFalse(local("unknown", List(65) { root("root$it", 2) }))
        assertFalse(local("unknown", listOf(root("", 2))))
        assertFalse(local("nested", listOf(root("local", 2), root("sd", 2), root("cloud", 0)), setOf("local", "sd")))
    }

    @Test
    fun `root and relationship query failures never authorize a tree`() {
        assertFalse(workspaceFolderIsLocal(TREE, "local", readRoots = { throw SecurityException() }, isChild = { false }))
        assertFalse(workspaceFolderIsLocal(TREE, "nested", readRoots = { listOf(root("local", 2), root("cloud", 0)) },
            isChild = { throw SecurityException() }))
    }

    private fun local(documentId: String, roots: List<WorkspaceProviderRoot>, parents: Set<String> = emptySet()): Boolean =
        workspaceFolderIsLocal(TREE, documentId, readRoots = { roots }, isChild = { it in parents })

    private fun root(id: String, flags: Int?) = WorkspaceProviderRoot(id, id, flags)

    companion object {
        private const val TREE = "content://local.example.documents/tree/selected"
    }
}
