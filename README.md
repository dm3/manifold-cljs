# manifold-cljs

[![Build Status](https://travis-ci.org/dm3/manifold-cljs.png?branch=master)](https://travis-ci.org/dm3/manifold-cljs)

A port of [Manifold](https://github.com/ztellman/manifold) to Clojurescript.

This port tracks the latest Manifold version as closely as possible. As per
[Clojurescript port](https://github.com/ztellman/manifold/issues/2) issue, Zach
wanted to keep the port separate from the main project - so here it is.
However, ~80% of the code was copied from the Clojure Manifold verbatim, so
there's a chance it might get ported back with reader conditionals.

The port hasn't been used in any serious applications yet, but there are some
tests. And they pass!

There are no blocking operations in Javascript, so some of the original
Manifold functions had to go.

## Usage

Add the following dependency to your project.clj or build.boot:

```clojure
[manifold-cljs "0.1.6-0"]
```

Then use it in your project:

```clojure
(ns example.project
  (:require [manifold-cljs.stream :as s]
            [manifold-cljs.deferred :as d]))
```

You can find several examples in the `examples/` directory.

## Extensions

* [Core.Async](https://github.com/clojure/core.async) adapter at [Manifold-cljs.Core.Async](https://github.com/dm3/manifold-cljs.core.async).

## Differences to Clojure implementation

### Executors

`manifold-cljs.executor` defines an `Executor` protocol with implementations
backed by `goog.async.nextTick`, `setTimeout`, synchronous execution and a
batched variation which takes another executor as its implementation. An
executor is selected while creating a Stream or a Deferred.

The call to `manifold-cljs.executor/executor` will always return the batched
`next-tick` executor by default, so all of the callbacks on Streams and
Deferreds are executed as tasks. Either as microtasks, if `setImmediate` is
available, or as tasks (`setTimeout`). This means that the code will behave
similarly to the way it would behave on the JVM if every stream and deferred
were executed on an executor.

On the JVM, the call to `manifold.executor/executor` will return no executor by
default, which will make the callbacks run on whichever thread triggered the
completion of the Deferred.

The difference becomes apparent in the following snippet:

```clojure
(let [s (s/stream), b (s/batch 2 s)]
  (s/put-all! s [1 2 3])
  (s/close! s)
  (assert (= [[1 2] [3]] (s/stream->seq b))))
```

The above succeeds when run on a single thread, but fails if run within a
`(manifold.executor/with-executor (manifold.executor/execute-pool) ...)` block.
The second result element - `[3]` - nevers gets delivered to the batched stream
as the source stream gets closed first. All the `Consumer` events registered on
the source get canceled.

### Blocking put/take

`stream/put` and `stream/take` have the same signature as their Clojure
counterparts, however setting the `blocking?` parameter to `true` will always
trigger an assertion error. Puts and takes in manifold-cljs will always return
a deferred result.  Consequently there is no way to synchronously connect
streams and `stream/isSynchronous` always returns false.

### Metadata

I couldn't find a protocol allowing mutation of metadata in Clojurescript. The
default deferred and stream implementations are mutable, so there are no `IMeta`
or `IWithMeta` implementations for streams/deferreds.

### Deferred protocols

The protocols for the Deferred have been moved to
`manifold-cljs.deferred.core`. The default implementation - to
`manifold-cljs.deferred.default`. This is analogous to what has been done to
streams.  This was done in order to avoid a cyclic dependency between
`manifold-cljs.deferred`, where the protocols used to live, and
`manifold-cljs.time`. Clojurescript is compiled statically and can't `require`
a namespace in the middle of the file.

Ideally we should propagate this change to the Clojure Manifold.

### Missing functions

* `manifold-cljs.stream/stream->seq` - inherently blocking
* `manifold-cljs.deferred`
    - `let-flow` - TODO: needs some advanced code walking
* `manifold-cljs.executor`
    - `instrumented-executor` - TODO: do we want this in Cljs?
    - `*stats-callbacks*` - TODO: do we want this in Cljs?
    - `utilization-executor` - not applicable
    - `execute-pool` - not applicable
    - `wait-pool` - not applicable
* `manifold-cljs.time`
    - `format-duration` - niche
    - `floor` - niche
    - `add` - niche
    - `IClock` - TODO: do we want this in Cljs?
    - `IMockClock` - TODO: do we want this in Cljs?
    - `mock-clock` - TODO: do we want this in Cljs?
    - `*clock*` - TODO: do we want this in Cljs?
    - `with-clock` - TODO: do we want this in Cljs?
    - `scheduled-executor->clock` - not applicable
* `manifold-cljs.utils`
    - `without-overflow` - not used
    - `fast-satisfies` - don't need
    - `with-lock` - don't need

### Cljs-only functions

* `manifold-cljs.deferred`
    - `time` - measure time taken to evaluate the body in a deferred

## TODO

* WeakMap dependency - this can somehow be compiled in by the GCC - how?
* unhandled error reporting - like goog.Deferred/Bluebird
* DEBUG stack traces - like goog.Deferred/Bluebird
* better logging - format to dev console?
* performance - currently ~3x slower than core.async on the `daisy` example
* `deferred/let-flow` - needs a different deep code walking impl/riddley replacement for Cljs

See [Closure Promise](https://github.com/google/closure-library/blob/master/closure/goog/promise/promise.js#L84) for more ideas.

## Patterns and Gotchas

Manifold is in need of best practices/patterns/gotchas library.

### Writing `d/loop`-based stream combinators

Many of the stream combinators, like `s/zip`, use `d/loop` inside to take from
a source stream and put into the destination stream. There is an additional
step needed to make a combinator like that work well when the source stream is
connected to other streams as well as combined via the combinator - passing
values through an intermediary stream. See [related Github
issue](https://github.com/ztellman/manifold/issues/87) for more info.

### Error handling

### Signalling "no more messages" upstream

### Upstream is really closed only after an additional put

Most people expect the `s/on-closed` callback to get called once the `s/close!`
is called on a stream. This is true if the callback is registered on the stream
that is being closed. However, in case we `s/close!` a downstream stream and
the callback is registered upstream, the close callback will only trigger once
the producer tries to put another value into the upstream. See
[this](https://github.com/ztellman/manifold/issues/82) and
[this](https://github.com/ztellman/manifold/issues/56) Github issues for more
info.

### `d/let-flow` won't handle deferred conditionals in a smart way

If you have a conditional clause where the condition is a deferred as well as a
result - the result deferred will be awaited even if the condition is falsey,
e.g.:

```clojure
(let [x (deferred-never-realized-unless-y)]
  (let-flow [y (deferred-false)]
    (if y x :ok))
```

The above will block on `x`, even though `y` realizes to `false`.

See [this](https://github.com/ztellman/manifold/issues/47) Github issue for more info.

## License

Copyright © 2016 Zach Tellman, Vadim Platonov

Distributed under the MIT License.
