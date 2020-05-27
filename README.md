MOON (bot)
==========

Inspired by Musk, my finances must be interplanetary (or at least inter-extraterrestrial). To get there we utilize:

run via sbt:
```
sbt run
```

build fatjar & run:
```
sbt assembly
find . -name "*assembly*.jar"
java -jar ./target/scala-2.13/MoonBot-assembly-0.1.jar
```

to test manual interactions:
```
sbt "runMain "moon.Cli monitorAll"
sbt "runMain "moon.Cli monitorOrder"
sbt "runMain moon.Cli --orderid be149218-9e8c-7367-122b-072c3f883a39 cancel"
sbt "runMain moon.Cli --ordertype stop --price 8656 --qty 30 ask"
sbt "runMain moon.Cli --ordertype market --qty 30 bid"
sbt "runMain rcb.Cli --ordertype limit --price 9000 --qty 30 bid"
...etc
```

To gather all ws jsons (as per ws application.conf's bitmex.wsUrl)
```
sbt "runMain moon.Cli monitorDebug" | grep "###" | sed 's/.*ws json: //'
```
