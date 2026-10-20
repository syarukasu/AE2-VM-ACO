# ACO Exact VM 0.1.6

Java 17 pure BigInteger bytecode library, derived from TaoLe-si/AE2-VM under LGPL-3.0. See NOTICE.txt and the repository LICENSE for provenance and attribution.

Build with `gradlew -p exact-engine jar sourcesJar`. This module has no Minecraft mod bootstrap or live world access. ACO embeds the game-library JAR together with the native-engine module compiled by ACO.

Recipe coefficients, requested amounts, inventory, execution counts, missing and used amounts remain exact BigInteger values. Limits fail explicitly; they do not saturate or truncate. Physical CPU execution remains owned by AE2 and external add-ons.

Development tests and runtime evidence are maintained in the local development checkout. This public branch contains production source and build configuration only; it makes no gameplay or TPS acceptance claim.
