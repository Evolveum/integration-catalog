FROM quay.io/keycloak/keycloak:26.2

COPY init.sh /opt/keycloak/init.sh

USER root
RUN chmod +x /opt/keycloak/init.sh
USER keycloak

ENTRYPOINT ["/opt/keycloak/init.sh"]
