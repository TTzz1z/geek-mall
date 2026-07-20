FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml pom.xml
COPY geek-common/pom.xml geek-common/pom.xml
COPY geek-auth-system/pom.xml geek-auth-system/pom.xml
COPY geek-gateway/pom.xml geek-gateway/pom.xml
COPY geek-merchant-product/pom.xml geek-merchant-product/pom.xml
COPY geek-inventory-fulfillment/pom.xml geek-inventory-fulfillment/pom.xml
COPY geek-search-recommend/pom.xml geek-search-recommend/pom.xml
COPY geek-trade/pom.xml geek-trade/pom.xml
COPY geek-member/pom.xml geek-member/pom.xml
COPY geek-marketing/pom.xml geek-marketing/pom.xml
COPY geek-aftersale-service/pom.xml geek-aftersale-service/pom.xml
COPY geek-service-center/pom.xml geek-service-center/pom.xml
COPY geek-settlement/pom.xml geek-settlement/pom.xml
COPY geek-ai/pom.xml geek-ai/pom.xml
RUN mvn -q -B dependency:go-offline -DskipTests
COPY . .
ARG MODULE
RUN mvn -q -B -pl ${MODULE} -am package -DskipTests && cp ${MODULE}/target/${MODULE}-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --uid 10001 geek
USER geek
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
