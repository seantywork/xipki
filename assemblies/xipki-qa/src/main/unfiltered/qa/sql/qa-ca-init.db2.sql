-- :: ca-int
-- IGNORE-ERROR
ALTER TABLE CRL  DROP CONSTRAINT FK_CRL_CA1;
-- IGNORE-ERROR
ALTER TABLE CERT DROP CONSTRAINT FK_CERT_CA1;
-- IGNORE-ERROR
ALTER TABLE CERT DROP CONSTRAINT FK_CERT_REQUESTOR1;
-- IGNORE-ERROR
ALTER TABLE CERT DROP CONSTRAINT FK_CERT_PROFILE1;
-- IGNORE-ERROR
ALTER TABLE PUBLISHQUEUE DROP CONSTRAINT FK_PUBLISHQUEUE_PUBLISHER1;
-- IGNORE-ERROR
ALTER TABLE PUBLISHQUEUE DROP CONSTRAINT FK_PUBLISHQUEUE_CERT1;

-- :: caconf-init
-- IGNORE-ERROR
ALTER TABLE CA DROP CONSTRAINT FK_CA_CRL_SIGNER1;
-- IGNORE-ERROR
ALTER TABLE CAALIAS DROP CONSTRAINT FK_CAALIAS_CA1;
-- IGNORE-ERROR
ALTER TABLE CA_HAS_REQUESTOR DROP CONSTRAINT FK_CA_HAS_REQUESTOR_REQUESTOR1;
-- IGNORE-ERROR
ALTER TABLE CA_HAS_REQUESTOR DROP CONSTRAINT FK_CA_HAS_REQUESTOR_CA1;
-- IGNORE-ERROR
ALTER TABLE CA_HAS_PUBLISHER DROP CONSTRAINT FK_CA_HAS_PUBLISHER_PUBLISHER1;
-- IGNORE-ERROR
ALTER TABLE CA_HAS_PUBLISHER DROP CONSTRAINT FK_CA_HAS_PUBLISHER_CA1;
-- IGNORE-ERROR
ALTER TABLE CA_HAS_PROFILE DROP CONSTRAINT FK_CA_HAS_PROFILE_PROFILE1;
-- IGNORE-ERROR
ALTER TABLE CA_HAS_PROFILE DROP CONSTRAINT FK_CA_HAS_PROFILE_CA1;

-- :: ca-int
DROP TABLE IF EXISTS DBSCHEMA;
DROP TABLE IF EXISTS PROFILE;
DROP TABLE IF EXISTS REQUESTOR;
DROP TABLE IF EXISTS CA;
DROP TABLE IF EXISTS CRL;
DROP TABLE IF EXISTS CERT;
DROP TABLE IF EXISTS PUBLISHQUEUE;

-- :: caconf-init
DROP TABLE IF EXISTS SYSTEM_EVENT;
DROP TABLE IF EXISTS KEYPAIR_GEN;
DROP TABLE IF EXISTS SIGNER;
DROP TABLE IF EXISTS PUBLISHER;
DROP TABLE IF EXISTS CAALIAS;
DROP TABLE IF EXISTS CA_HAS_REQUESTOR;
DROP TABLE IF EXISTS CA_HAS_PUBLISHER;
DROP TABLE IF EXISTS CA_HAS_PROFILE;