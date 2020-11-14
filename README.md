# Streamliner

This is the source code for the prototype implementation of the technique presented in [Eliminating Abstraction Overhead of Java Stream Pipelines using Ahead-of-Time Program Optimization](https://cs.au.dk/~amoeller/papers/streamliner/paper.pdf).

## Build instructions

The project uses maven for dependency management. It is compiled with

```bash
mvn compile
```

Unit tests can be run with

```
mvn test
```

Most of the tests should run successfully, but it is expected that some 16-17 tests will fail.

## Reproduce RQ1 results

The `misc/jmh.sh` script will optimise the micro benchmarks specified in `dk.casa.streamliner.asm.TransformASM` , and use [JMH](https://github.com/openjdk/jmh) to benchmark the programs.

## Reproduce RQ2 results

Clone and build the benchmarked projects with the `RQ2/clone_and_build.py` script.

Run the analysis and optimisation on the projects with the command:

```
env MAVEN_OPTS="-Xmx20g -Xss512m --add-opens java.base/java.lang.module=ALL-UNNAMED" mvn compile exec:java -Dexec.mainClass=dk.casa.streamliner.asm.RQ2.Experiment
```

Which pre-analysis to use is chosen with a command-line argument. For instance, to use the SPARK-powered analysis, add `-Dexec.args="spark"` to the command. The `"wala"` analysis is similarly available.

Additional options can be specified for SPARK by passing them as a second argument:
`-Dexec.args="spark cs-demand:true,on-fly-cg:true"`. A list of available options can be found [here](https://soot-build.cs.uni-paderborn.de/public/origin/develop/soot/soot-develop/options/soot_options.htm#phase_5_2).

Be aware that these analyses contain bugs and issues that cause many type queries to fail. Some stats on query results will be shown when these analyses are used.

## Further use

See `dk.casa.streamliner.asm.OptimizeSkeleton` for a guide on how to apply the tool in more contexts.
