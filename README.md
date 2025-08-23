# F1 Tipping - Backend
F1 Tipping er en tippeside der deltakerne tipper på sluttresultatene av Formel 1 sesongen. I tillegg tippes det på hvem som kommer på første- og tiendeplass i hvert løp gjennom året.

Dette repoet er koden for backenden til nettsiden. Koden for frontenden ligger [her](https://github.com/Vebb02/f1-frontend).

## Oversikt
1. [Utvikler](#utvikler)
2. [Krav](#krav)
3. [Oppsett](#oppsett-for-første-gang)
4. [Kjøre Applikasjonen](#kjøre-applikasjonen)

## Utvikler
**Vebjørn Øiestad**

## Krav
* Java JDK 17 eller nyere
* Maven

## Oppsett for Første Gang

1. Lag en ny fil i **`/src/main/resources`** med navnet **`secret.properties`**.
2. Fyll inn følgende informasjon:

```
spring.security.oauth2.client.registration.google.client-id={id}
spring.security.oauth2.client.registration.google.client-secret={secret}
spring.mail.username={email}
spring.mail.password={app password}
spring.datasource.username={database username}
spring.datasource.password={database password}
```

## Kjøre Applikasjonen

For å starte applikasjonen, kjør følgende kommando:

```
mvn spring-boot:run
```
