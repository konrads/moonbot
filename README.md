MOON (bot)
==========

...We're going to the moon... To get there we utilize:

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
sbt "runMain moon.Cli monitorAll"
sbt "runMain moon.Cli monitorOrder"
sbt "runMain moon.Cli --orderid be149218-9e8c-7367-122b-072c3f883a39 cancel"
sbt "runMain moon.Cli --ordertype stop --side sell --price 8656 --qty 30 order"
sbt "runMain moon.Cli --ordertype market --side sell --qty 30 order"
sbt "runMain moon.Cli --ordertype limit --side buy --price 9000 --qty 30 order"
...etc
```

To gather all ws jsons (as per ws application.conf's bitmex.wsUrl)
```
sbt "runMain moon.Cli monitorDebug" | grep "###" | sed 's/.*ws json: //'
```

To start graphite/grafana:
```
cd bin
./devops.sh  # for options
./devops.sh grafana-build
./devops.sh grafana-run
./devops.sh grafana-bootstrap-run
```
