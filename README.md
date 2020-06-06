MOON (bot)
==========

Run
---

First setup `application.private.conf` in $PROJECT_DIR:
```
bitmex.url = "https://www.bitmex.com"
# bitmex.url = "https://testnet.bitmex.com"
bitmex.wsUrl = "wss://testnet.bitmex.com/realtime?subscribe=orderBook10:XBTUSD,trade:XBTUSD,instrument:.BXBT,funding:XBTUSD"
# bitmex.wsUrl = "wss://www.bitmex.com/realtime?subscribe=orderBook10:XBTUSD,trade:XBTUSD,instrument:.BXBT,funding:XBTUSD"
bitmex.apiKey = "XXX"
bitmex.apiSecret = "YYY"
```

Setup with metrics and point browser @ `localhost:81`:
```
# setup graphite/grafana
cd bin
./devops.sh  # for options
./devops.sh grafana-build
./devops.sh grafana-run
./devops.sh grafana-bootstrap-run
```

Start bot:
```
sbt run
```


Helpful operations
------------------

build fatjar & run:
```
sbt assembly
find . -name "*assembly*.jar"
java -jar ./target/scala-2.13/MoonBot-assembly-0.1.jar
```

Test manual REST/Websockets interactions:
```
sbt "runMain moon.Cli monitorAll"
sbt "runMain moon.Cli monitorOrder"
sbt "runMain moon.Cli --orderid be149218-9e8c-7367-122b-072c3f883a39 cancel"
sbt "runMain moon.Cli --ordertype stop --side sell --price 8656 --qty 30 order"
sbt "runMain moon.Cli --ordertype market --side sell --qty 30 order"
sbt "runMain moon.Cli --ordertype limit --side buy --price 9000 --qty 30 order"
...etc
```

Gather all ws jsons (as per ws application.conf's bitmex.wsUrl)
```
sbt "runMain moon.Cli monitorDebug" | grep "###" | sed 's/.*ws json: //'
```
