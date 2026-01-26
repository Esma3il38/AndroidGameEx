package com.techted89.gameex

data class MemoryRegion(
    val startAddress: Long,
    val endAddress: Long,
    val size: Long,
    val permissions: String,
    val filename: String
)
