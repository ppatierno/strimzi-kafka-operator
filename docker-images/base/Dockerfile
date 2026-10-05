#####
# Build the helper container image for downloading prerequisites
#####
FROM registry.access.redhat.com/ubi9/ubi-minimal:latest AS downloader

ARG TARGETOS
ARG TARGETARCH

#####
# Get Tini
#####
ENV TINI_VERSION=v0.19.0
ENV TINI_SHA256_AMD64=93dcc18adc78c65a028a84799ecf8ad40c936fdfc5f2a57b1acda5a8117fa82c
ENV TINI_SHA256_ARM64=07952557df20bfd2a95f9bef198b445e006171969499a1d361bd9e6f8e5e0e81
ENV TINI_SHA256_PPC64LE=3f658420974768e40810001a038c29d003728c5fe86da211cff5059e48cfdfde
ENV TINI_SHA256_S390X=931b70a182af879ca249ae9de87ef68423121b38d235c78997fafc680ceab32d

#####
# Get Java 25 EA JRE
#####
ENV JAVA25_JRE_VERSION=jdk-25.0.5%2B7-ea-beta
ENV JAVA25_JRE_SHA256_AMD64=1e74e28cf15ad57681d0a5d2b9f3c59fede42a695b3ebf599785964d1bafef1a
ENV JAVA25_JRE_SHA256_ARM64=2711f9cbce1ca685d9ec699d838ba10eab58d482658a0f0c5fa7ea94f45c0b09
ENV JAVA25_JRE_SHA256_PPC64LE=d95c24c67dc914c9feb60bfb35e3b3eeaa5416d519ff156378554f10916b5a15
ENV JAVA25_JRE_SHA256_S390X=baad469ec4bc427294cc3b86a516b962b61e8a35b2b9d3d8635c50a7a6f80533

RUN set -ex; \
    microdnf install -y tar gzip; \
    if [[ "${TARGETOS}/${TARGETARCH}" = "linux/ppc64le" ]]; then \
        curl -s -L https://github.com/adoptium/temurin25-binaries/releases/download/${JAVA25_JRE_VERSION}/OpenJDK25U-jre_ppc64le_linux_hotspot_25.0.5_7-ea.tar.gz -o /tmp/java25-jre.tar.gz; \
        echo "${JAVA25_JRE_SHA256_PPC64LE} */tmp/java25-jre.tar.gz" | sha256sum -c; \
    elif [[ "${TARGETOS}/${TARGETARCH}" = "linux/arm64" ]]; then \
        curl -s -L https://github.com/adoptium/temurin25-binaries/releases/download/${JAVA25_JRE_VERSION}/OpenJDK25U-jre_aarch64_linux_hotspot_25.0.5_7-ea.tar.gz -o /tmp/java25-jre.tar.gz; \
        echo "${JAVA25_JRE_SHA256_ARM64} */tmp/java25-jre.tar.gz" | sha256sum -c; \
    elif [[ "${TARGETOS}/${TARGETARCH}" = "linux/s390x" ]]; then \
        curl -s -L https://github.com/adoptium/temurin25-binaries/releases/download/${JAVA25_JRE_VERSION}/OpenJDK25U-jre_s390x_linux_hotspot_25.0.5_7-ea.tar.gz -o /tmp/java25-jre.tar.gz; \
        echo "${JAVA25_JRE_SHA256_S390X} */tmp/java25-jre.tar.gz" | sha256sum -c; \
    else \
        curl -s -L https://github.com/adoptium/temurin25-binaries/releases/download/${JAVA25_JRE_VERSION}/OpenJDK25U-jre_x64_linux_hotspot_25.0.5_7-ea.tar.gz -o /tmp/java25-jre.tar.gz; \
        echo "${JAVA25_JRE_SHA256_AMD64} */tmp/java25-jre.tar.gz" | sha256sum -c; \
    fi; \
    mkdir -p /java25; \
    tar -xzf /tmp/java25-jre.tar.gz -C /java25 --strip-components=1; \
    rm /tmp/java25-jre.tar.gz; \
    microdnf clean all

RUN set -ex; \
    if [[ "${TARGETOS}/${TARGETARCH}" = "linux/ppc64le" ]]; then \
        curl -s -L https://github.com/krallin/tini/releases/download/${TINI_VERSION}/tini-ppc64le -o /usr/bin/tini; \
        echo "${TINI_SHA256_PPC64LE} */usr/bin/tini" | sha256sum -c; \
        chmod +x /usr/bin/tini; \
    elif [[ "${TARGETOS}/${TARGETARCH}" = "linux/arm64" ]]; then \
        curl -s -L https://github.com/krallin/tini/releases/download/${TINI_VERSION}/tini-arm64 -o /usr/bin/tini; \
        echo "${TINI_SHA256_ARM64} */usr/bin/tini" | sha256sum -c; \
        chmod +x /usr/bin/tini; \
    elif [[ "${TARGETOS}/${TARGETARCH}" = "linux/s390x" ]]; then \
        curl -s -L https://github.com/krallin/tini/releases/download/${TINI_VERSION}/tini-s390x -o /usr/bin/tini; \
        echo "${TINI_SHA256_S390X} */usr/bin/tini" | sha256sum -c; \
        chmod +x /usr/bin/tini; \
    else \
        curl -s -L https://github.com/krallin/tini/releases/download/${TINI_VERSION}/tini -o /usr/bin/tini; \
        echo "${TINI_SHA256_AMD64} */usr/bin/tini" | sha256sum -c; \
        chmod +x /usr/bin/tini; \
    fi

#####
# Build the main container image
#####
FROM registry.access.redhat.com/ubi9/ubi-minimal:latest

LABEL org.opencontainers.image.source='https://github.com/strimzi/strimzi-kafka-operator'

ARG JAVA_VERSION=21
ARG TARGETOS
ARG TARGETARCH
ARG strimzi_version

LABEL name='base' \
    vendor='Strimzi' \
    version="${strimzi_version}" \
    release="${strimzi_version}" \
    summary='Base image of the Strimzi Kafka Operator project.' \
    description='Strimzi Kafka Operator base image, containing common utilities across all component images.'

USER root

RUN microdnf update -y \
    && microdnf --setopt=install_weak_deps=0 --setopt=tsflags=nodocs install -y  \
            # Needed to run Java applications \
            # java-${JAVA_VERSION}-openjdk-headless \
            # Needed to work with TLS certificates in our shell scripts as well as from our own Java code \
            openssl \
            # Needed to add non-root users to our container images \
            shadow-utils \
    && microdnf reinstall -y tzdata \
    && microdnf clean all -y

# ENV JAVA_HOME=/usr/lib/jvm/jre-${JAVA_VERSION}

#####
# Copy Tini and Java 25 from the downloader stage
#####
COPY --from=downloader /usr/bin/tini /usr/bin/tini
COPY --from=downloader /java25 /usr/lib/jvm/java-25

ENV JAVA_HOME=/usr/lib/jvm/java-25
ENV PATH="${JAVA_HOME}/bin:${PATH}"
