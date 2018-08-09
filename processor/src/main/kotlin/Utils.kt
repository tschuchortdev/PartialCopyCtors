package com.tschuchort.copyctor.processor

internal fun List<String>.concat() = fold("", String::plus)

internal fun <T> List<T>.intersperse(elem: T) = flatMap { listOf(it, elem) }.dropLast(1)

internal inline fun <reified R : Any> List<*>.castList() = map { it as R }