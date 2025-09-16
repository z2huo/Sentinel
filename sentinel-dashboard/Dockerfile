
#FROM eclipse-temurin:8-jre-noble
FROM eclipse-temurin:17-jre-noble
#FROM eclipse-temurin:21-jre-noble

COPY ./packages/sentinel-dashboard-1.8.8.jar /home/sentinel-dashboard.jar

ENV JAVA_OPTS '-Dserver.port=8080 -Dcsp.sentinel.dashboard.server=localhost:8080'

RUN chmod -R +x /home/sentinel-dashboard.jar

EXPOSE 8080

CMD java ${JAVA_OPTS} -jar /home/sentinel-dashboard.jar