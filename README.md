MOON (bot)
==========

Run
---

First setup `application.private.conf` in $PROJECT_DIR:
```
bitmex.url = "https://www.bitmex.com"
# or bitmex.url = "https://testnet.bitmex.com"
bitmex.wsUrl = "wss://testnet.bitmex.com/realtime?subscribe=orderBook10:XBTUSD,trade:XBTUSD,instrument:.BXBT,funding:XBTUSD"
# or bitmex.wsUrl = "wss://www.bitmex.com/realtime?subscribe=orderBook10:XBTUSD,trade:XBTUSD,instrument:.BXBT,funding:XBTUSD"
bitmex.apiKey = "XXX"
bitmex.apiSecret = "YYY"
```

Setup with metrics and point browser @ `localhost:81` (user/pwd - defined as `grafana_user`/`grafana_pwd` in [devops.sh](bin/devops.sh)):
```
# setup generic graphite/grafana
cd bin
./devops.sh  # to show options
./devops.sh grafana-build
./devops.sh grafana-run
# setup moon user/dashboard in another shell
./devops.sh grafana-bootstrap-run
```

Start bot.

**Note**: *this clears position and cancels orders, as part of the `actor restart` policy. To avoid this, set `bot.flushSessionOnRestart = false` in `application.conf`.*
```
sbt run  # Note: clears position and cancels position orders!
# or sbt assembly && java -jar ./target/scala-2.13/MoonBot-assembly-0.1.jar
```


Design
------
Bot communicates with `Bitmex` via [WsGateway](src/main/scala/moon/WsGateway.scala) for listening of websocket events (order lifecycle notifications, trade notifications, orderbook notifications, etc)
and [RestGateway](src/main/scala/moon/RestGateway.scala) for Rest requests (issue order, cancel, amend, etc). 

[OrchestratorActor](src/main/scala/moon/OrchestratorActor.scala) is the **BIG** FSM that handles both websocket notifications and async REST responses. It divides main states into:
- init - wait till enough info is captured to start trading
- idle - wait till decision is made to go long or short
- opening long - issue long limit order, amending price if moves in the expected direction, flip to idle if price moves in the opposite direction
- closing long - issue short limit takeProfit and trailing stop orders, once filled - cancel the other
- opening short - issue short limit order, amending price if moves in the expected direction, flip to idle if price moves in the opposite direction
- closing short - issue long limit takeProfit and trailing stop orders, once filled - cancel the other

[Ledger](src/main/scala/moon/Ledger.scala) is the place to record all events, and query for eg. whether to trade long/short. Also place to keep metrics.

[Metrics](src/main/scala/moon/Metrics.scala) sends graphite packets, potentially adding JVM/system ones.

[Cli](src/main/scala/moon/Cli.scala) allows for command line interactions with REST/websockets, for manual testing.


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

Gather all ws json events, for analysis:
```
sbt "runMain moon.Cli monitorDebug" | grep "###" | sed 's/.*ws json: //'
```
