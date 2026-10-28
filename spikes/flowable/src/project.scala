//> using scala 3.9.0
//> using jvm temurin:21
//> using dep dev.zio::zio:2.1.27
//> using dep org.postgresql:postgresql:42.7.14
//> using dep com.zaxxer:HikariCP:7.1.0
//> using dep org.slf4j:slf4j-simple:2.0.17
//> using resourceDir resources
//> using javaOpt -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -Dorg.slf4j.simpleLogger.showDateTime=true -Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss.SSS
// The Flowable version is NOT pinned here: run.sh passes --dep org.flowable:flowable-engine:$FLOWABLE_VERSION
// so the same code can be compiled and run against 7.2.0 and 8.0.0.
