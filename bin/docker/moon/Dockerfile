FROM mozilla/sbt
COPY stage /src
RUN mkdir /var/log/moon
RUN cd /src && sbt assembly
ENTRYPOINT sleep 30 cd /src && java -jar ./target/scala-2.13/MoonBot-assembly-0.1.jar
# provide in command:
# --config application-yabol-xbtusd.conf
# or
# --config application-yabol-ethusd.conf

