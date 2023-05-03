libraryDependencies ++= List(
  "org.apache.spark" %% "spark-catalyst" % "3.4.0" % "provided",

  "com.clickhouse" % "clickhouse-jdbc" % "0.3.2-patch11",
  "com.zaxxer" % "HikariCP" % "5.0.1",

  "com.github.bigwheel" %% "util-backports" % "2.1",
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.14.2",
  "com.fasterxml.jackson.module" % "jackson-module-parameter-names" % "2.14.2",
  "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % "2.14.2",
  "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % "2.14.2",
  "ch.qos.logback" % "logback-classic" % "1.4.6",
)