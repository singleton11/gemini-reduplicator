FROM openjdk:24-slim

COPY build/tasks/_gemini-reduplicator_executableJarJvm/gemini-reduplicator-jvm-executable.jar /home/app.jar

CMD java -jar /home/app.jar
