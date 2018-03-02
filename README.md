# PartialCopyCtors
This is an annotation processor that generates partial copy constructors for Kotlin data classes. A partial copy constructor allows you to initialize all inherited/overriden properties with a superclass instance:

```kotlin
open class Base(open val baseField: Int)
    
@PartialCopyCtors
data class Derived(val a: Float, override val baseField: Int) : Base(baseField)

val base = Base(123)

val derived: Derived = DerivedFactory.copy(
		from = base,
		a = 1.3f
		// baseField is initialized automatically with the value of base.baseField
)
````


This is particularly useful if you have data classes that share a lot of members with a superclass, for example when encoding states for a state machine in a functional architecture like MVI:

```kotlin

sealed class State {
	abstract val items: List<Item>
	abstract val internetConnected: Boolean
	...

	data class Error(
		val cause: String,
		override val items: List<Item>,
		override val internetConnected: Boolean,
		...
	) : State
}

fun update(oldState: State, event: Event): State = when(event) {
	// we only have to supply the old state and additionally values for the new state 
	// instead of manually copying all the common property values 
	Event.Error -> ErrorFactory.copy(from = oldState, cause = it.cause)
	...
}
````

### Limitations:
- due to the very awful annotation processing API, all parameters for the copy function have to be specified even when a default value exists in the original constructor
- some Kotlin types like `kotlin.collections.List` may lead to compilation errors because they are converted to `java.util.List` before annotation processing
