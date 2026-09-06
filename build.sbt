ThisBuild / scalaVersion := "3.3.6"

lazy val root = (project in file("."))
  .settings(
    name := "causeway-mini",
    scalacOptions += "-deprecation",
    libraryDependencies ++= Seq(
      "org.eclipse.jgit" % "org.eclipse.jgit" % "7.3.0.202506031305-r",
      "com.lihaoyi" %% "upickle" % "4.1.0"
    )
  )
