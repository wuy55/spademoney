# ADR-0009: jqwik pinned to 1.9.x

- **Status:** Accepted
- **Date:** 2026-07-20
- **Deciders:** Spencer Wu

## Context

Property-based testing is load-bearing here: the zero-sum invariant and the
`Money` construction rules are asserted over generated inputs rather than over
hand-picked examples, and jqwik is the library that does it.

On adding the dependency I found that the 1.10 line ships two things the 1.9 line
does not. The first is text emitted into test output addressed to AI coding
agents, instructing them to disregard the test results they are reading. The
second is a licensing clause restricting use of the library in AI-assisted
development.

The second is the maintainer's call to make and I have no argument with it. The
first is the operational problem: test output is something an agent reads, and
text in it that tells the reader to ignore what the tests said is prompt
injection arriving through a build artifact. It does not matter whether it is
meant seriously — a build artifact is an untrusted input channel, and I would not
want a green-or-red signal passing through instructions about how to interpret it.

## Decision

Pin jqwik to exactly `1.9.3`, the last release before that boundary:

```xml
<dependency>
    <groupId>net.jqwik</groupId>
    <artifactId>jqwik</artifactId>
    <version>1.9.3</version>
    <scope>test</scope>
</dependency>
```

The version is stated explicitly rather than inherited or ranged, so no automatic
dependency update can cross the 1.10 boundary without a human doing it
deliberately.

## Consequences

The test signal stays a test signal. Nothing in the build output is addressed to
a reader other than the developer.

Licensing stays EPL-2.0, which is unambiguous for this project's use.

The costs are ordinary and worth naming. 1.10+ features are unavailable, and the
gap will widen. Security patches to the 1.10 line will not be picked up
automatically, so this pin has to be revisited rather than left forever; the
mitigating fact is that jqwik is a test-scope dependency and never ships in a
running artifact. Dependency bumps for this one coordinate are manual and
reviewed, which is friction deliberately introduced at a spot where automation was
the risk.

Revisit if the upstream project changes course, or if a maintained fork appears
that keeps the property-testing engine without the output channel.

The generalisable point, which is why this record exists rather than a comment in
the POM: dependencies became an injection surface the moment agents started
reading build output. A pin is a blunt instrument, but the alternative is
trusting every future release of every test dependency not to address the reader.
