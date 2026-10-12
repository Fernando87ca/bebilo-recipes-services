FROM eclipse-temurin:23-jre

ADD build/distributions/recipe-services-1.0-SNAPSHOT.tar /opt/
WORKDIR /opt/recipe-services-1.0-SNAPSHOT

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["/opt/recipe-services-1.0-SNAPSHOT/bin/recipe-services"]
