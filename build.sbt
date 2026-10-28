ThisBuild / scalaVersion := "3.3.4"
ThisBuild / version      := "0.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "causeway-mini",
    scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:imports"),
    libraryDependencies ++= Seq(
      "org.eclipse.jgit" % "org.eclipse.jgit" % "6.10.0.202406032230-r",
      "org.xerial"       % "sqlite-jdbc"      % "3.46.1.3",
      "com.lihaoyi"     %% "upickle"          % "4.0.2",
      "org.slf4j"        % "slf4j-nop"        % "2.0.16",
      "org.scalameta"   %% "munit"            % "1.0.2" % Test
    ),
    // Tests build real git repos and open SQLite files; keep them out of sbt's own JVM.
    Test / fork := true
  )
