FROM openjdk:24-slim

COPY build/tasks/_gemini-reduplicator_uberJar/uber-jar.jar /home/app.jar

CMD java -jar /home/app.jar
