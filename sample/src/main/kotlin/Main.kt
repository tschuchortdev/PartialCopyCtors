package com.tschuchort.copyctor.sample

import com.tschuchort.copyctor.processor.PartialCopyCtors

interface Interface1
interface Interface2

abstract class Base1() : Interface1 {
	abstract val baseField: Int
}

abstract class Base3 : Base1()

abstract class Base2<T>(open val baseField: Int) : Interface1 {
}

@PartialCopyCtors
data class A(private val a: Int = 1) : Interface2

@PartialCopyCtors
data class B(val a: Int = 1, override val baseField: Int) : Base3(), Interface2

@PartialCopyCtors
data class C(val a: Int = 1, val b: Int) : Base1(), Interface2 {
	override val baseField = b

	constructor(a: Int) : this(a, b = 0)
}

@PartialCopyCtors
data class D(val a: Int = 1, val b: List<List<Int>>, override val baseField: Int) : Base2<Unit>(1), Interface2

@PartialCopyCtors
data class E<T : List<*>, out S : List<T>>(val a: T, val b: List<out S>, override val baseField: Int) : Base2<Unit>(baseField), Interface2

@PartialCopyCtors
data class F(val a: List<Int>, val b: java.util.List<Int>, val c: MutableList<java.util.List<Int>>, override val baseField: Int)
	: Base2<Unit>(baseField)

fun main(args: Array<String>) {
	println("hello")
	EFactory
	DFactory
	FFactory
}