# Kairos

A functional reactive programming (FRP) library for Kotlin.

This library is **experimental** and should not be used for general production
code. The APIs within are subject to change, and there may be bugs.

## About FRP

Functional reactive programming is a type of reactive programming system that
follows a set of clear and composable rules, without sacrificing consistency.
FRP exposes an API that should be familiar to those versed in Kotlin `Flow`.

### Details for nerds

`Kairos` implements an applicative / monadic flavor of FRP, using a push-pull
methodology to allow for efficient updates.

"Real" functional reactive programming should be specified with denotational
semantics ([wikipedia](https://en.wikipedia.org/wiki/Denotational_semantics)):
you can view the semantics for `Kairos` [here](docs/semantics.md).

## Usage

First, stand up a new `KairosNetwork`. All reactive events and state is kept
consistent within a single network.

``` kotlin
val coroutineScope: CoroutineScope = ...
val network = coroutineScope.launchKairosNetwork()
```

You can use the `KairosNetwork` to stand-up a network of reactive events and
state. Events are modeled with `Events`, and states with `State`.

``` kotlin
suspend fun activate(network: KairosNetwork) {
    network.activateSpec {
        val input = network.mutableEvents<Unit>()
        // Launch a long-running side-effect that emits to the network
        // every second.
        launchEffect {
            while (true) {
                input.emit(Unit)
                delay(1.seconds)
            }
        }
        // Accumulate state
        val count: State<Int> = input.foldState { _, i -> i + 1 }
        // Observe events to perform side-effects in reaction to them
        input.observe {
            println("Got event ${count.sample()} at time: ${System.currentTimeMillis()}")
        }
    }
}
```

`KairosNetwork.activateSpec` will suspend indefinitely; cancelling the invocation
will tear-down all effects and obervers running within the lambda.

## Resources

- [Cheatsheet for those coming from Kotlin Flow](docs/flow-to-kairos-cheatsheet.md)
