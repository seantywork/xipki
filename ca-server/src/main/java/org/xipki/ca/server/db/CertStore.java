/*
 *
 * Copyright (c) 2013 - 2020 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.ca.server.db;

import static org.xipki.ca.api.OperationException.ErrorCode.BAD_REQUEST;
import static org.xipki.ca.api.OperationException.ErrorCode.CERT_REVOKED;
import static org.xipki.ca.api.OperationException.ErrorCode.CERT_UNREVOKED;
import static org.xipki.ca.api.OperationException.ErrorCode.CRL_FAILURE;
import static org.xipki.ca.api.OperationException.ErrorCode.DATABASE_FAILURE;
import static org.xipki.ca.api.OperationException.ErrorCode.NOT_PERMITTED;
import static org.xipki.ca.api.OperationException.ErrorCode.SYSTEM_FAILURE;
import static org.xipki.util.Args.notBlank;
import static org.xipki.util.Args.notNull;
import static org.xipki.util.Args.positive;

import java.io.IOException;
import java.math.BigInteger;
import java.security.cert.CRLException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERPrintableString;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.CertificateList;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.TBSCertList.CRLEntry;
import org.bouncycastle.cert.X509CRLHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.ca.api.CertWithDbId;
import org.xipki.ca.api.CertificateInfo;
import org.xipki.ca.api.NameId;
import org.xipki.ca.api.OperationException;
import org.xipki.ca.api.mgmt.CertListInfo;
import org.xipki.ca.api.mgmt.CertListOrderBy;
import org.xipki.ca.api.mgmt.CertWithRevocationInfo;
import org.xipki.ca.api.mgmt.entry.CaHasUserEntry;
import org.xipki.ca.server.CaIdNameMap;
import org.xipki.ca.server.CaUtil;
import org.xipki.ca.server.CertRevInfoWithSerial;
import org.xipki.ca.server.PasswordHash;
import org.xipki.ca.server.UniqueIdGenerator;
import org.xipki.datasource.DataAccessException;
import org.xipki.datasource.DataSourceWrapper;
import org.xipki.security.CertRevocationInfo;
import org.xipki.security.CrlReason;
import org.xipki.security.HashAlgo;
import org.xipki.security.ObjectIdentifiers;
import org.xipki.security.X509Cert;
import org.xipki.security.util.X509Util;
import org.xipki.util.Base64;
import org.xipki.util.LogUtil;
import org.xipki.util.LruCache;
import org.xipki.util.StringUtil;

/**
 * CA database store.
 *
 * @author Lijun Liao
 * @since 2.0.0
 */

public class CertStore extends CertStoreBase {

  public static enum CertStatus {

    UNKNOWN,
    REVOKED,
    GOOD

  } // class CertStatus

  public static class SerialWithId {

    private long id;

    private BigInteger serial;

    public SerialWithId(long id, BigInteger serial) {
      this.id = id;
      this.serial = serial;
    }

    public BigInteger getSerial() {
      return serial;
    }

    public long getId() {
      return id;
    }

  }

  public static class SystemEvent {

    private final String name;

    private final String owner;

    private final long eventTime;

    public SystemEvent(String name, String owner, long eventTime) {
      this.name = notBlank(name, "name");
      this.owner = notBlank(owner, "owner");
      this.eventTime = eventTime;
    }

    public String getName() {
      return name;
    }

    public String getOwner() {
      return owner;
    }

    public long getEventTime() {
      return eventTime;
    }

  } // class SystemEvent

  public static class KnowCertResult {

    public static final KnowCertResult UNKNOWN = new KnowCertResult(false, null);

    private final boolean known;

    private final Integer userId;

    public KnowCertResult(boolean known, Integer userId) {
      this.known = known;
      this.userId = userId;
    }

    public boolean isKnown() {
      return known;
    }

    public Integer getUserId() {
      return userId;
    }

  }

  private static final Logger LOG = LoggerFactory.getLogger(CertStore.class);

  private final String sqlCertForId;

  private final String sqlCertWithRevInfo;

  private final String sqlCertInfo;

  private final String sqlCertprofileForCertId;

  private final String sqlActiveUserInfoForName;

  private final String sqlActiveUserNameForId;

  private final String sqlCaHasUser;

  private final String sqlKnowsCertForSerial;

  private final String sqlCertStatusForSubjectFp;

  private final String sqlCertforSubjectIssued;

  private final String sqlLatestSerialForSubjectLike;

  private final String sqlCrl;

  private final String sqlCrlWithNo;

  private final String sqlReqIdForSerial;

  private final String sqlReqForId;

  private final String sqlSelectUnrevokedSn100;

  private final String sqlSelectUnrevokedSn;

  private final LruCache<Integer, String> cacheSqlCidFromPublishQueue = new LruCache<>(5);

  private final LruCache<Integer, String> cacheSqlExpiredSerials = new LruCache<>(5);

  private final LruCache<Integer, String> cacheSqlSuspendedSerials = new LruCache<>(5);

  private final LruCache<Integer, String> cacheSqlRevokedCerts = new LruCache<>(5);

  private final LruCache<Integer, String> cacheSqlSerials = new LruCache<>(5);

  private final LruCache<Integer, String> cacheSqlSerialsRevoked = new LruCache<>(5);

  private final UniqueIdGenerator idGenerator;

  private final AtomicInteger cachedCrlId = new AtomicInteger(0);

  public CertStore(DataSourceWrapper datasource, UniqueIdGenerator idGenerator)
      throws DataAccessException {
    super(datasource);

    this.idGenerator = notNull(idGenerator, "idGenerator");

    this.sqlCertForId = buildSelectFirstSql("PID,RID,REV,RR,RT,RIT,CERT FROM CERT WHERE ID=?");
    this.sqlCertWithRevInfo = buildSelectFirstSql(
        "ID,REV,RR,RT,RIT,PID,CERT FROM CERT WHERE CA_ID=? AND SN=?");
    this.sqlCertInfo = buildSelectFirstSql(
        "PID,RID,REV,RR,RT,RIT,CERT FROM CERT WHERE CA_ID=? AND SN=?");
    this.sqlCertprofileForCertId = buildSelectFirstSql("PID FROM CERT WHERE ID=? AND CA_ID=?");
    this.sqlActiveUserInfoForName = buildSelectFirstSql(
        "ID,PASSWORD FROM TUSER WHERE NAME=? AND ACTIVE=1");
    this.sqlActiveUserNameForId = buildSelectFirstSql("NAME FROM TUSER WHERE ID=? AND ACTIVE=1");
    this.sqlCaHasUser = buildSelectFirstSql(
        "PERMISSION,PROFILES FROM CA_HAS_USER WHERE CA_ID=? AND USER_ID=?");
    this.sqlKnowsCertForSerial = buildSelectFirstSql("UID FROM CERT WHERE SN=? AND CA_ID=?");
    this.sqlCertStatusForSubjectFp = buildSelectFirstSql("REV FROM CERT WHERE FP_S=? AND CA_ID=?");
    this.sqlCertforSubjectIssued = buildSelectFirstSql("ID FROM CERT WHERE CA_ID=? AND FP_S=?");
    this.sqlReqIdForSerial = buildSelectFirstSql("REQCERT.RID as REQ_ID FROM REQCERT INNER JOIN "
        + "CERT ON CERT.CA_ID=? AND CERT.SN=? AND REQCERT.CID=CERT.ID");
    this.sqlReqForId = buildSelectFirstSql("DATA FROM REQUEST WHERE ID=?");
    this.sqlLatestSerialForSubjectLike = buildSelectFirstSql("NBEFORE DESC",
        "SUBJECT FROM CERT WHERE SUBJECT LIKE ?");
    this.sqlCrl = buildSelectFirstSql("THISUPDATE DESC", "THISUPDATE,CRL FROM CRL WHERE CA_ID=?");
    this.sqlCrlWithNo = buildSelectFirstSql("THISUPDATE DESC",
        "THISUPDATE,CRL FROM CRL WHERE CA_ID=? AND CRL_NO=?");

    this.sqlSelectUnrevokedSn = buildSelectFirstSql("LUPDATE FROM CERT WHERE REV=0 AND SN=?");
    final String prefix = "SN,LUPDATE FROM CERT WHERE REV=0 AND SN";
    this.sqlSelectUnrevokedSn100 = buildArraySql(datasource, prefix, 100);
  } // constructor

  public boolean addCert(CertificateInfo certInfo) {
    notNull(certInfo, "certInfo");

    byte[] encodedCert = null;

    try {
      CertWithDbId cert = certInfo.getCert();
      byte[] transactionId = certInfo.getTransactionId();
      X500Name reqSubject = certInfo.getRequestedSubject();

      long certId = idGenerator.nextId();

      String subjectText = X509Util.cutText(cert.getCert().getSubjectRfc4519Text(), maxX500nameLen);
      long fpSubject = X509Util.fpCanonicalizedName(cert.getCert().getSubject());

      String reqSubjectText = null;
      Long fpReqSubject = null;
      if (reqSubject != null) {
        fpReqSubject = X509Util.fpCanonicalizedName(reqSubject);
        if (fpSubject == fpReqSubject) {
          fpReqSubject = null;
        } else {
          reqSubjectText = X509Util.cutX500Name(CaUtil.sortX509Name(reqSubject), maxX500nameLen);
        }
      }

      encodedCert = cert.getCert().getEncoded();
      String b64FpCert = HashAlgo.SHA1.base64Hash(encodedCert);
      String tid = (transactionId == null) ? null : Base64.encodeToString(transactionId);

      X509Cert cert0 = cert.getCert();
      boolean isEeCert = cert0.getBasicConstraints() == -1;

      execUpdatePrepStmt0(dbSchemaVersion < 5 ? SQL_ADD_CERT_V4 : SQL_ADD_CERT,
          col2Long(certId), col2Long(System.currentTimeMillis() / 1000), // currentTimeSeconds
          col2Str(cert0.getSerialNumber().toString(16)),
          col2Str(subjectText),  col2Long(fpSubject), col2Long(fpReqSubject),
          col2Long(cert0.getNotBefore().getTime() / 1000), // notBeforeSeconds
          col2Long(cert0.getNotAfter().getTime() / 1000), // notAfterSeconds
          col2Bool(false), col2Int(certInfo.getProfile().getId()),
          col2Int(certInfo.getIssuer().getId()), col2Int(certInfo.getRequestor().getId()),
          col2Int(certInfo.getUser()), col2Int(isEeCert ? 1 : 0),
          col2Int(certInfo.getReqType().getCode()),
          col2Str(tid), col2Str(b64FpCert), col2Str(reqSubjectText),
          col2Int(0), // in this version we set CRL_SCOPE to fixed value 0
          col2Str(Base64.encodeToString(encodedCert)));

      cert.setCertId(certId);
    } catch (Exception ex) {
      LOG.error("could not save certificate {}: {}. Message: {}",
          certInfo.getCert().getCert().getSubject(),
          encodedCert == null ? "null" : Base64.encodeToString(encodedCert, true), ex.getMessage());
      LOG.debug("error", ex);
      return false;
    }

    return true;
  } // method addCert

  public void addToPublishQueue(NameId publisher, long certId, NameId ca)
      throws OperationException {
    notNull(ca, "ca");
    execUpdatePrepStmt0(SQL_INSERT_PUBLISHQUEUE,
        col2Int(publisher.getId()), col2Int(ca.getId()), col2Long(certId));
  } // method addToPublishQueue

  public void removeFromPublishQueue(NameId publisher, long certId) throws OperationException {
    execUpdatePrepStmt0(SQL_REMOVE_PUBLISHQUEUE, col2Int(publisher.getId()), col2Long(certId));
  } // method removeFromPublishQueue

  public void clearPublishQueue(NameId ca, NameId publisher) throws OperationException {
    StringBuilder sqlBuilder = new StringBuilder(80);
    sqlBuilder.append("DELETE FROM PUBLISHQUEUE");

    List<SqlColumn2> params = new ArrayList<>(2);
    if (ca != null || publisher != null) {
      sqlBuilder.append(" WHERE");
      if (ca != null) {
        sqlBuilder.append(" CA_ID=?");
        params.add(col2Int(ca.getId()));

        if (publisher != null) {
          sqlBuilder.append(" AND");
        }
      }
      if (publisher != null) {
        sqlBuilder.append(" PID=?");
        params.add(col2Int(publisher.getId()));
      }
    }

    execUpdatePrepStmt0(sqlBuilder.toString(), params.toArray(new SqlColumn2[0]));
  } // method clearPublishQueue

  public long getMaxFullCrlNumber(NameId ca) throws OperationException {
    return getMaxCrlNumber(ca, SQL_MAX_FULL_CRLNO);
  }

  public long getMaxCrlNumber(NameId ca) throws OperationException {
    return getMaxCrlNumber(ca, SQL_MAX_CRLNO);
  }

  private long getMaxCrlNumber(NameId ca, String sql) throws OperationException {
    notNull(ca, "ca");

    long maxCrlNumber = execQueryLongPrepStmt(sql, col2Int(ca.getId()));
    return (maxCrlNumber < 0) ? 0 : maxCrlNumber;
  } // method getMaxCrlNumber

  public long getThisUpdateOfCurrentCrl(NameId ca, boolean deltaCrl) throws OperationException {
    notNull(ca, "ca");

    return execQueryLongPrepStmt(SQL_MAX_THISUPDAATE_CRL,
        col2Int(ca.getId()), col2Int(deltaCrl ? 1 : 0));
  } // method getThisUpdateOfCurrentCrl

  public void addCrl(NameId ca, X509CRLHolder crl) throws OperationException, CRLException {
    notNulls(ca, "ca", crl, "crl");

    Extensions extns = crl.getExtensions();
    byte[] extnValue = X509Util.getCoreExtValue(extns, Extension.cRLNumber);
    // CHECKSTYLE:SKIP
    Long crlNumber = (extnValue == null) ? null
                      : ASN1Integer.getInstance(extnValue).getPositiveValue().longValue();

    extnValue = X509Util.getCoreExtValue(extns, Extension.deltaCRLIndicator);
    Long baseCrlNumber = null;
    if (extnValue != null) {
      baseCrlNumber = ASN1Integer.getInstance(extnValue).getPositiveValue().longValue();
    }

    final String sql = SQL_ADD_CRL;
    int currentMaxCrlId = (int) getMax("CRL", "ID");
    int crlId = Math.max(cachedCrlId.get(), currentMaxCrlId) + 1;
    cachedCrlId.set(crlId);

    String b64Crl;
    try {
      b64Crl = Base64.encodeToString(crl.getEncoded());
    } catch (IOException ex) {
      throw new CRLException(ex.getMessage(), ex);
    }

    execUpdatePrepStmt0(sql, col2Int(crlId), col2Int(ca.getId()), col2Long(crlNumber),
        col2Long(crl.getThisUpdate().getTime() / 1000),
        col2Long(getDateSeconds(crl.getNextUpdate())), col2Bool((baseCrlNumber != null)),
        // in this version we set CRL_SCOPE to fixed value 0
        col2Long(baseCrlNumber), col2Int(0), col2Str(b64Crl));
  } // method addCrl

  public CertWithRevocationInfo revokeCert(NameId ca, BigInteger serialNumber,
      CertRevocationInfo revInfo, boolean force, CaIdNameMap idNameMap) throws OperationException {
    notNulls(ca, "ca", serialNumber, "serialNumber", revInfo, "revInfo");

    CertWithRevocationInfo certWithRevInfo =
        getCertWithRevocationInfo(ca.getId(), serialNumber, idNameMap);
    if (certWithRevInfo == null) {
      LOG.warn("certificate with CA={} and serialNumber={} does not exist",
          ca.getName(), LogUtil.formatCsn(serialNumber));
      return null;
    }

    CertRevocationInfo currentRevInfo = certWithRevInfo.getRevInfo();
    if (currentRevInfo != null) {
      CrlReason currentReason = currentRevInfo.getReason();
      if (currentReason == CrlReason.CERTIFICATE_HOLD) {
        if (revInfo.getReason() == CrlReason.CERTIFICATE_HOLD) {
          throw new OperationException(CERT_REVOKED, "certificate already revoked with the "
              + "requested reason " + currentReason.getDescription());
        } else {
          revInfo.setRevocationTime(currentRevInfo.getRevocationTime());
          revInfo.setInvalidityTime(currentRevInfo.getInvalidityTime());
        }
      } else if (!force) {
        throw new OperationException(CERT_REVOKED,
          "certificate already revoked with reason " + currentReason.getDescription());
      }
    }

    Long invTimeSeconds = null;
    if (revInfo.getInvalidityTime() != null) {
      invTimeSeconds = revInfo.getInvalidityTime().getTime() / 1000;
    }

    int count = execUpdatePrepStmt0(SQL_REVOKE_CERT,
        col2Long(System.currentTimeMillis() / 1000), col2Bool(true),
        col2Long(revInfo.getRevocationTime().getTime() / 1000), // revTimeSeconds
        col2Long(invTimeSeconds), col2Int(revInfo.getReason().getCode()),
        col2Long(certWithRevInfo.getCert().getCertId().longValue())); // certId
    if (count != 1) {
      String message = (count > 1) ? count + " rows modified, but exactly one is expected"
          : "no row is modified, but exactly one is expected";
      throw new OperationException(SYSTEM_FAILURE, message);
    }

    certWithRevInfo.setRevInfo(revInfo);
    return certWithRevInfo;
  } // method revokeCert

  public CertWithRevocationInfo revokeSuspendedCert(NameId ca, BigInteger serialNumber,
      CrlReason reason, CaIdNameMap idNameMap) throws OperationException {
    notNulls(ca, "ca", serialNumber, "serialNumber", reason, "reason");

    CertWithRevocationInfo certWithRevInfo =
        getCertWithRevocationInfo(ca.getId(), serialNumber, idNameMap);
    if (certWithRevInfo == null) {
      LOG.warn("certificate with CA={} and serialNumber={} does not exist",
          ca.getName(), LogUtil.formatCsn(serialNumber));
      return null;
    }

    CertRevocationInfo currentRevInfo = certWithRevInfo.getRevInfo();
    if (currentRevInfo == null) {
      throw new OperationException(CERT_UNREVOKED, "certificate is not revoked");
    }

    CrlReason currentReason = currentRevInfo.getReason();
    if (currentReason != CrlReason.CERTIFICATE_HOLD) {
      throw new OperationException(CERT_REVOKED, "certificate is revoked but not with reason "
          + CrlReason.CERTIFICATE_HOLD.getDescription());
    }

    int count = execUpdatePrepStmt0(SQL_REVOKE_SUSPENDED_CERT,
        col2Long(System.currentTimeMillis() / 1000), col2Int(reason.getCode()),
        col2Long(certWithRevInfo.getCert().getCertId().longValue())); // certId

    if (count != 1) {
      String message = (count > 1) ? count + " rows modified, but exactly one is expected"
            : "no row is modified, but exactly one is expected";
      throw new OperationException(SYSTEM_FAILURE, message);
    }

    currentRevInfo.setReason(reason);
    return certWithRevInfo;
  } // method revokeSuspendedCert

  public CertWithDbId unrevokeCert(NameId ca, BigInteger serialNumber, boolean force,
      CaIdNameMap idNamMap) throws OperationException {
    notNulls(ca, "ca", serialNumber, "serialNumber");

    CertWithRevocationInfo certWithRevInfo =
        getCertWithRevocationInfo(ca.getId(), serialNumber, idNamMap);
    if (certWithRevInfo == null) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("certificate with CA={} and serialNumber={} does not exist",
            ca.getName(), LogUtil.formatCsn(serialNumber));
      }
      return null;
    }

    CertRevocationInfo currentRevInfo = certWithRevInfo.getRevInfo();
    if (currentRevInfo == null) {
      throw new OperationException(CERT_UNREVOKED, "certificate is not revoked");
    }

    CrlReason currentReason = currentRevInfo.getReason();
    if (!force) {
      if (currentReason != CrlReason.CERTIFICATE_HOLD) {
        throw new OperationException(NOT_PERMITTED, "could not unrevoke certificate revoked with "
            + "reason " + currentReason.getDescription());
      }
    }

    SqlColumn2 nullInt = new SqlColumn2(ColumnType.INT, null);
    int count = execUpdatePrepStmt0(
        "UPDATE CERT SET LUPDATE=?,REV=?,RT=?,RIT=?,RR=? WHERE ID=?",
        col2Long(System.currentTimeMillis() / 1000), // currentTimeSeconds
        col2Bool(false), nullInt, nullInt, nullInt,
        col2Long(certWithRevInfo.getCert().getCertId().longValue())); // certId

    if (count != 1) {
      String message = (count > 1) ? count + " rows modified, but exactly one is expected"
          : "no row is modified, but exactly one is expected";
      throw new OperationException(SYSTEM_FAILURE, message);
    }

    return certWithRevInfo.getCert();
  } // method unrevokeCert

  public void removeCert(NameId ca, BigInteger serialNumber) throws OperationException {
    notNulls(ca, "ca", serialNumber, "serialNumber");

    int count = execUpdatePrepStmt0(SQL_REMOVE_CERT,
        col2Int(ca.getId()), col2Str(serialNumber.toString(16)));

    if (count != 1) {
      String message = (count > 1) ? count + " rows modified, but exactly one is expected"
          : "no row is modified, but exactly one is expected";
      throw new OperationException(SYSTEM_FAILURE, message);
    }
  } // method removeCert

  public List<Long> getPublishQueueEntries(NameId ca, NameId publisher, int numEntries)
      throws OperationException {
    String sql = cacheSqlCidFromPublishQueue.get(numEntries);
    if (sql == null) {
      sql = datasource.buildSelectFirstSql(numEntries, "CID ASC",
              "CID FROM PUBLISHQUEUE WHERE PID=? AND CA_ID=?");
      cacheSqlCidFromPublishQueue.put(numEntries, sql);
    }

    List<ResultRow> rows = execQueryPrepStmt0(sql, col3s(COL3_CID),
        col2Int(publisher.getId()), col2Int(ca.getId()));

    List<Long> ret = new ArrayList<>();
    for (ResultRow rs : rows) {
      long certId = rs.getLong("CID");
      if (!ret.contains(certId)) {
        ret.add(certId);
      }
      if (ret.size() >= numEntries) {
        break;
      }
    }
    return ret;
  } // method getPublishQueueEntries

  public long getCountOfCerts(NameId ca, boolean onlyRevoked) throws OperationException {
    final String sql = onlyRevoked ? "SELECT COUNT(*) FROM CERT WHERE CA_ID=? AND REV=1"
                    : "SELECT COUNT(*) FROM CERT WHERE CA_ID=?";

    return execQueryLongPrepStmt(sql, col2Int(ca.getId()));
  } // method getCountOfCerts

  public List<SerialWithId> getSerialNumbers(NameId ca,  long startId, int numEntries,
      boolean onlyRevoked) throws OperationException {
    notNulls(ca, "ca", numEntries, "numEntries");

    LruCache<Integer, String> cache = onlyRevoked ? cacheSqlSerialsRevoked : cacheSqlSerials;
    String sql = cache.get(numEntries);
    if (sql == null) {
      String coreSql = "ID,SN FROM CERT WHERE ID>? AND CA_ID=?";
      if (onlyRevoked) {
        coreSql += "AND REV=1";
      }
      sql = datasource.buildSelectFirstSql(numEntries, "ID ASC", coreSql);
      cache.put(numEntries, sql);
    }

    return getSerialWithIds(sql, numEntries, col2Long(startId - 1), col2Int(ca.getId()));
  } // method getSerialNumbers

  public List<SerialWithId> getSerialNumbers(NameId ca, Date notExpiredAt, long startId,
      int numEntries, boolean onlyRevoked, boolean onlyCaCerts, boolean onlyUserCerts)
      throws OperationException {
    notNulls(ca, "ca", numEntries, "numEntries");

    if (onlyCaCerts && onlyUserCerts) {
      throw new IllegalArgumentException("onlyCaCerts and onlyUserCerts cannot be both of true");
    }

    List<SqlColumn2> params = new ArrayList<>(4);
    params.add(col2Long(startId - 1));
    params.add(col2Int(ca.getId()));
    if (notExpiredAt != null) {
      params.add(col2Long(notExpiredAt.getTime() / 1000 + 1));
    }

    boolean withEe = onlyCaCerts || onlyUserCerts;
    if (withEe) {
      params.add(col2Bool(onlyUserCerts));
    }

    String sqlCore = StringUtil.concat("ID,SN FROM CERT WHERE ID>? AND CA_ID=?",
        (notExpiredAt != null ? " AND NAFTER>?" : ""),
        (onlyRevoked ? " AND REV=1" : ""), (withEe ? " AND EE=?" : ""));
    String sql = datasource.buildSelectFirstSql(numEntries, "ID ASC", sqlCore);

    return getSerialWithIds(sql, numEntries, params.toArray(new SqlColumn2[0]));
  } // method getSerialNumbers

  private List<SerialWithId> getSerialWithIds(String sql, int numEntries, SqlColumn2... params)
      throws OperationException {
    List<ResultRow> rows = execQueryPrepStmt0(sql, col3s(COL3_ID), params);

    List<SerialWithId> ret = new ArrayList<>();
    for (ResultRow rs : rows) {
      ret.add(new SerialWithId(rs.getLong("ID"), new BigInteger(rs.getString("SN"), 16)));
      if (ret.size() >= numEntries) {
        break;
      }
    }

    return ret;
  }

  public List<BigInteger> getExpiredSerialNumbers(NameId ca, long expiredAt, int numEntries)
      throws OperationException {
    notNull(ca, "ca");
    positive(numEntries, "numEntries");

    String sql = cacheSqlExpiredSerials.get(numEntries);
    if (sql == null) {
      sql = datasource.buildSelectFirstSql(numEntries, "SN FROM CERT WHERE CA_ID=? AND NAFTER<?");
      cacheSqlExpiredSerials.put(numEntries, sql);
    }

    return getSerialNumbers0(sql, numEntries, col2Int(ca.getId()), col2Long(expiredAt));
  } // method getExpiredSerialNumbers

  public List<BigInteger> getSuspendedCertSerials(NameId ca, long latestLastUpdate, int numEntries)
      throws OperationException {
    notNull(ca, "ca");
    positive(numEntries, "numEntries");

    String sql = cacheSqlSuspendedSerials.get(numEntries);
    if (sql == null) {
      sql = datasource.buildSelectFirstSql(numEntries,
          "SN FROM CERT WHERE CA_ID=? AND LUPDATE<? AND RR=?");
      cacheSqlSuspendedSerials.put(numEntries, sql);
    }

    return getSerialNumbers0(sql, numEntries, col2Int(ca.getId()), col2Long(latestLastUpdate + 1),
            col2Int(CrlReason.CERTIFICATE_HOLD.getCode()));
  } // method getSuspendedCertIds

  private List<BigInteger> getSerialNumbers0(String sql, int numEntries, SqlColumn2... params)
      throws OperationException {
    List<ResultRow> rows = execQueryPrepStmt0(sql, null, params);
    List<BigInteger> ret = new ArrayList<>();
    for (ResultRow row : rows) {
      ret.add(new BigInteger(row.getString("SN"), 16));
      if (ret.size() >= numEntries) {
        break;
      }
    }
    return ret;
  } // method getExpiredSerialNumbers

  public byte[] getEncodedCrl(NameId ca) throws OperationException {
    notNull(ca, "ca");

    List<ResultRow> rows = execQueryPrepStmt0(sqlCrl, col3s(COL3_THISUPDATE), col2Int(ca.getId()));
    long currentThisUpdate = 0;

    String b64Crl = null;
    // iterate all entries to make sure that the latest CRL will be returned
    for (ResultRow rs : rows) {
      long thisUpdate = rs.getLong("THISUPDATE");
      if (thisUpdate >= currentThisUpdate) {
        b64Crl = rs.getString("CRL");
        currentThisUpdate = thisUpdate;
      }
    }

    return (b64Crl == null) ? null : Base64.decodeFast(b64Crl);
  } // method getEncodedCrl

  public byte[] getEncodedCrl(NameId ca, BigInteger crlNumber) throws OperationException {
    notNull(ca, "ca");

    if (crlNumber == null) {
      return getEncodedCrl(ca);
    }

    ResultRow rs = execQuery1PrepStmt0(sqlCrlWithNo, null,
        col2Int(ca.getId()), col2Long(crlNumber.longValue()));

    return rs == null ? null : Base64.decodeFast(rs.getString("CRL"));
  } // method getEncodedCrl

  public int cleanupCrls(NameId ca, int numCrls) throws OperationException {
    notNull(ca, "ca");
    positive(numCrls, "numCrls");

    List<Long> crlNumbers = new LinkedList<>();

    List<ResultRow> rows = execQueryPrepStmt0("SELECT CRL_NO FROM CRL WHERE CA_ID=? AND DELTACRL=?",
        col3s(COL3_CRL_NO), col2Int(ca.getId()), col2Bool(false));
    for (ResultRow rs : rows) {
      crlNumbers.add(rs.getLong("CRL_NO"));
    }

    int size = crlNumbers.size();
    Collections.sort(crlNumbers);

    int numCrlsToDelete = size - numCrls;
    if (numCrlsToDelete < 1) {
      return 0;
    }

    long crlNumber = crlNumbers.get(numCrlsToDelete - 1);
    execUpdatePrepStmt0("DELETE FROM CRL WHERE CA_ID=? AND CRL_NO<?",
        col2Int(ca.getId()), col2Long(crlNumber + 1));

    return numCrlsToDelete;
  } // method cleanupCrls

  public CertificateInfo getCertForId(NameId ca, X509Cert caCert, long certId,
      CaIdNameMap idNameMap) throws OperationException {
    notNulls(ca, "ca", caCert, "caCert", idNameMap, "idNameMap");

    final SqlColumn3[] resultColumns =
        col3s(COL3_PID, COL3_RID, COL3_REV, COL3_RR, COL3_RT, COL3_RIT);
    ResultRow rs = execQuery1PrepStmt0(sqlCertForId, resultColumns, col2Long(certId));
    if (rs == null) {
      return null;
    }

    X509Cert cert = parseCert(Base64.decodeFast(rs.getString("CERT")));
    CertWithDbId certWithMeta = new CertWithDbId(cert);
    certWithMeta.setCertId(certId);
    CertificateInfo certInfo = new CertificateInfo(certWithMeta, null, ca, caCert,
        idNameMap.getCertprofile(rs.getInt("PID")), idNameMap.getRequestor(rs.getInt("RID")));
    certInfo.setRevocationInfo(buildCertRevInfo(rs));
    return certInfo;
  } // method getCertForId

  public CertWithRevocationInfo getCertWithRevocationInfo(int caId, BigInteger serial,
      CaIdNameMap idNameMap) throws OperationException {
    notNulls(serial, "serial", idNameMap, "idNameMap");

    ResultRow rs = execQuery1PrepStmt0(sqlCertWithRevInfo,
        col3s(COL3_PID, COL3_RID, COL3_REV, COL3_RR, COL3_RT, COL3_RIT),
        col2Int(caId), col2Str(serial.toString(16)));
    if (rs == null) {
      return null;
    }

    X509Cert cert = parseCert(Base64.decodeFast(rs.getString("CERT")));
    CertWithDbId certWithMeta = new CertWithDbId(cert);
    certWithMeta.setCertId(rs.getLong("ID"));

    CertWithRevocationInfo ret = new CertWithRevocationInfo();
    ret.setCertprofile(idNameMap.getCertprofileName(rs.getInt("PID")));
    ret.setCert(certWithMeta);
    ret.setRevInfo(buildCertRevInfo(rs));
    return ret;
  } // method getCertWithRevocationInfo

  public CertificateInfo getCertInfo(NameId ca, X509Cert caCert, BigInteger serial,
      CaIdNameMap idNameMap) throws OperationException {
    notNulls(ca, "ca", caCert, "caCert", idNameMap, "idNameMap", serial, "serial");

    ResultRow rs = execQuery1PrepStmt0(sqlCertInfo,
        col3s(COL3_PID, COL3_RID, COL3_REV, COL3_RR, COL3_RT, COL3_RIT),
        col2Int(ca.getId()), col2Str(serial.toString(16)));
    if (rs == null) {
      return null;
    }

    byte[] encodedCert = Base64.decodeFast(rs.getString("CERT"));
    CertWithDbId certWithMeta = new CertWithDbId(parseCert(encodedCert));

    CertificateInfo certInfo = new CertificateInfo(certWithMeta, null, ca, caCert,
        idNameMap.getCertprofile(rs.getInt("PID")),
        idNameMap.getRequestor(rs.getInt("RID")));

    certInfo.setRevocationInfo(buildCertRevInfo(rs));
    return certInfo;
  } // method getCertInfo

  public Integer getCertprofileForCertId(NameId ca, long cid) throws OperationException {
    notNull(ca, "ca");
    ResultRow rs = execQuery1PrepStmt0(sqlCertprofileForCertId, col3s(COL3_PID),
        col2Long(cid), col2Int(ca.getId()));
    return rs == null ? null : rs.getInt("PID");
  } // method getCertprofileForId

  /**
   * Get certificates for given subject and transactionId.
   *
   * @param subjectName Subject of Certificate or requested Subject.
   * @param transactionId will only be considered if there are more than one certificate
   *     matches the subject.
   * @return certificates for given subject and transactionId.
   * @throws OperationException
   *           If error occurs.
   */
  public List<X509Cert> getCert(X500Name subjectName, byte[] transactionId)
      throws OperationException {
    final String sql = (transactionId != null)
        ? "SELECT CERT FROM CERT WHERE TID=? AND (FP_S=? OR FP_RS=?)"
        : "SELECT CERT FROM CERT WHERE FP_S=? OR FP_RS=?";

    long fpSubject = X509Util.fpCanonicalizedName(subjectName);
    List<X509Cert> certs = new LinkedList<>();

    SqlColumn2[] params = new SqlColumn2[transactionId == null ? 2 : 3];
    int idx = 0;
    if (transactionId != null) {
      params[idx++] = col2Str(Base64.encodeToString(transactionId));
    }
    params[idx++] = col2Long(fpSubject);
    params[idx++] = col2Long(fpSubject);

    List<ResultRow> rows = execQueryPrepStmt0(sql, null, params);
    for (ResultRow rs : rows) {
      certs.add(parseCert(Base64.decodeFast(rs.getString("CERT"))));
    }
    return certs;
  } // method getCert

  public byte[] getCertRequest(NameId ca, BigInteger serialNumber) throws OperationException {
    notNulls(ca, "ca", serialNumber, "serialNumber");

    ResultRow row = execQuery1PrepStmt0(sqlReqIdForSerial, col3s(COL3_REQ_ID),
        col2Int(ca.getId()), col2Str(serialNumber.toString(16)));

    if (row == null) {
      return null;
    }

    row = execQuery1PrepStmt0(sqlReqForId, null, col2Long(row.getLong("REQ_ID")));
    return (row == null) ? null : Base64.decodeFast(row.getString("DATA"));
  } // method getCertRequest

  public List<CertListInfo> listCerts(NameId ca, X500Name subjectPattern, Date validFrom,
      Date validTo, CertListOrderBy orderBy, int numEntries) throws OperationException {
    notNull(ca, "ca");
    positive(numEntries, "numEntries");

    StringBuilder sb = new StringBuilder(200);
    sb.append("SN,NBEFORE,NAFTER,SUBJECT FROM CERT WHERE CA_ID=?");

    List<SqlColumn2> params = new ArrayList<>(4);
    params.add(col2Int(ca.getId()));

    if (validFrom != null) {
      sb.append(" AND NBEFORE<?");
      params.add(col2Long(validFrom.getTime() / 1000 - 1));
    }
    if (validTo != null) {
      sb.append(" AND NAFTER>?");
      params.add(col2Long(validTo.getTime() / 1000));
    }

    if (subjectPattern != null) {
      sb.append(" AND SUBJECT LIKE ?");

      StringBuilder buffer = new StringBuilder(100);
      buffer.append("%");
      RDN[] rdns = subjectPattern.getRDNs();
      for (int i = 0; i < rdns.length; i++) {
        X500Name rdnName = new X500Name(new RDN[]{rdns[i]});
        String rdnStr = X509Util.getRfc4519Name(rdnName);
        if (rdnStr.indexOf('%') != -1) {
          throw new OperationException(BAD_REQUEST,
              "the character '%' is not allowed in subjectPattern");
        }
        if (rdnStr.indexOf('*') != -1) {
          rdnStr = rdnStr.replace('*', '%');
        }
        buffer.append(rdnStr);
        buffer.append("%");
      }
      params.add(col2Str(buffer.toString()));
    }

    String sortByStr = null;
    if (orderBy != null) {
      if (orderBy == CertListOrderBy.NOT_BEFORE) {
        sortByStr = "NBEFORE";
      } else if (orderBy == CertListOrderBy.NOT_BEFORE_DESC) {
        sortByStr = "NBEFORE DESC";
      } else if (orderBy == CertListOrderBy.NOT_AFTER) {
        sortByStr = "NAFTER";
      } else if (orderBy == CertListOrderBy.NOT_AFTER_DESC) {
        sortByStr = "NAFTER DESC";
      } else if (orderBy == CertListOrderBy.SUBJECT) {
        sortByStr = "SUBJECT";
      } else if (orderBy == CertListOrderBy.SUBJECT_DESC) {
        sortByStr = "SUBJECT DESC";
      } else {
        throw new IllegalStateException("unknown CertListOrderBy " + orderBy);
      }
    }

    final String sql = datasource.buildSelectFirstSql(numEntries, sortByStr, sb.toString());
    List<ResultRow> rows = execQueryPrepStmt0(sql, col3s(COL3_NBEFORE, COL3_NAFTER),
                            params.toArray(new SqlColumn2[0]));

    List<CertListInfo> ret = new LinkedList<>();
    for (ResultRow rs : rows) {
      CertListInfo info = new CertListInfo(new BigInteger(rs.getString("SN"), 16),
          rs.getString("SUBJECT"), new Date(rs.getLong("NBEFORE") * 1000),
          new Date(rs.getLong("NAFTER") * 1000));
      ret.add(info);
    }
    return ret;
  } // method listCerts

  public NameId authenticateUser(String user, byte[] password) throws OperationException {
    final String sql = sqlActiveUserInfoForName;

    ResultRow rs = execQuery1PrepStmt0(sql, col3s(col3Int("ID")), col2Str(user));
    if (rs == null) {
      return null;
    }

    int id = rs.getInt("ID");
    String expPasswordText = rs.getString("PASSWORD");

    if (StringUtil.isBlank(expPasswordText)) {
      return null;
    }

    boolean valid = PasswordHash.validatePassword(password, expPasswordText);
    return valid ? new NameId(id, user) : null;
  } // method authenticateUser

  public String getUsername(int id) throws OperationException {
    ResultRow rs = execQuery1PrepStmt0(sqlActiveUserNameForId, null, col2Int(id));
    return rs == null ? null : rs.getString("NAME");
  } // method getUsername

  public CaHasUserEntry getCaHasUser(NameId ca, NameId user) throws OperationException {
    ResultRow rs = execQuery1PrepStmt0(sqlCaHasUser, col3s(COL3_PERMISSION),
                    col2Int(ca.getId()), col2Int(user.getId()));
    if (rs == null) {
      return null;
    }

    List<String> list = StringUtil.split(rs.getString("PROFILES"), ",");
    Set<String> profiles = (list == null) ? null : new HashSet<>(list);

    CaHasUserEntry entry = new CaHasUserEntry(user);
    entry.setPermission(rs.getInt("PERMISSION"));
    entry.setProfiles(profiles);
    return entry;
  } // method getCaHasUser

  public KnowCertResult knowsCertForSerial(NameId ca, BigInteger serial) throws OperationException {
    notNull(serial, "serial");

    ResultRow rs = execQuery1PrepStmt0(sqlKnowsCertForSerial, col3s(COL3_UID),
                    col2Str(serial.toString(16)), col2Int(ca.getId()));
    return rs == null ? KnowCertResult.UNKNOWN : new KnowCertResult(true, rs.getInt("UID"));
  } // method knowsCertForSerial

  public List<CertRevInfoWithSerial> getRevokedCerts(NameId ca, Date notExpiredAt, long startId,
      int numEntries) throws OperationException {
    notNulls(ca, "ca", notExpiredAt, "notExpiredAt");
    positive(numEntries, "numEntries");

    String sql = cacheSqlRevokedCerts.get(numEntries);
    if (sql == null) {
      String coreSql = "ID,SN,RR,RT,RIT FROM CERT WHERE ID>? AND CA_ID=? AND REV=1 AND NAFTER>?";
      sql = datasource.buildSelectFirstSql(numEntries, "ID ASC", coreSql);
      cacheSqlRevokedCerts.put(numEntries, sql);
    }

    SqlColumn3[] resultColumns = col3s(COL3_RIT, COL3_ID, COL3_RR, COL3_RT);
    List<ResultRow> rows = execQueryPrepStmt0(sql, resultColumns,
        col2Long(startId - 1), col2Int(ca.getId()), col2Long(notExpiredAt.getTime() / 1000 + 1));

    List<CertRevInfoWithSerial> ret = new LinkedList<>();
    for (ResultRow rs : rows) {
      long revInvalidityTime = rs.getLong("RIT");
      Date invalidityTime = (revInvalidityTime == 0) ? null : new Date(1000 * revInvalidityTime);
      CertRevInfoWithSerial revInfo = new CertRevInfoWithSerial(rs.getLong("ID"),
          new BigInteger(rs.getString("SN"), 16), rs.getInt("RR"), // revReason
          new Date(1000 * rs.getLong("RT")), invalidityTime);
      ret.add(revInfo);
    }

    return ret;
  } // method getRevokedCerts

  public List<CertRevInfoWithSerial> getCertsForDeltaCrl(NameId ca, BigInteger baseCrlNumber,
      Date notExpiredAt) throws OperationException {
    notNulls(ca, "ca", notExpiredAt, "notExpiredAt", baseCrlNumber, "baseCrlNumber");

    // Get the Base FullCRL
    byte[] encodedCrl = getEncodedCrl(ca, baseCrlNumber);
    CertificateList crl = CertificateList.getInstance(encodedCrl);
    // Get revoked certs in CRL
    Enumeration<?> revokedCertsInCrl = crl.getRevokedCertificateEnumeration();

    Set<BigInteger> allSnSet = null;

    boolean supportInSql = datasource.getDatabaseType().supportsInArray();
    List<BigInteger> snList = new LinkedList<>();

    List<CertRevInfoWithSerial> ret = new LinkedList<>();

    PreparedStatement ps = null;
    try {
      while (revokedCertsInCrl.hasMoreElements()) {
        CRLEntry crlEntry = (CRLEntry) revokedCertsInCrl.nextElement();
        if (allSnSet == null) {
          // guess the size of revoked certificate, very rough
          int averageSize = encodedCrl.length / crlEntry.getEncoded().length;
          allSnSet = new HashSet<>((int) (1.1 * averageSize));
        }

        BigInteger sn = crlEntry.getUserCertificate().getPositiveValue();
        snList.add(sn);
        allSnSet.add(sn);

        if (!supportInSql) {
          continue;
        }

        if (snList.size() == 100) {
          // check whether revoked certificates have been unrevoked.

          // due to the memory consumption do not use the executeQueryPreparedStament0() method.
          if (ps == null) {
            ps = prepareStatement(sqlSelectUnrevokedSn100);
          }

          for (int i = 1; i < 101; i++) {
            ps.setString(i, snList.get(i - 1).toString(16));
          }
          snList.clear();

          ResultSet rs = ps.executeQuery();
          try {
            while (rs.next()) {
              ret.add(new CertRevInfoWithSerial(0L, new BigInteger(rs.getString("SN"), 16),
                  CrlReason.REMOVE_FROM_CRL, // reason
                  new Date(100L * rs.getLong("LUPDATE")), //revocationTime,
                  null)); // invalidityTime
            }
          } finally {
            datasource.releaseResources(null, rs);
          }
        }
      }
    } catch (SQLException ex) {
      throw new OperationException(DATABASE_FAILURE,
          datasource.translate(sqlSelectUnrevokedSn100, ex).getMessage());
    } catch (IOException ex) {
      throw new OperationException(CRL_FAILURE, ex.getMessage());
    } finally {
      datasource.releaseResources(ps, null);
    }

    if (!snList.isEmpty()) {
      // check whether revoked certificates have been unrevoked.
      ps = prepareStatement(sqlSelectUnrevokedSn);
      try {
        for (BigInteger sn : snList) {
          ps.setString(1, sn.toString(16));
          ResultSet rs = ps.executeQuery();
          try {
            if (rs.next()) {
              ret.add(new CertRevInfoWithSerial(0L, sn, CrlReason.REMOVE_FROM_CRL,
                  new Date(100L * rs.getLong("LUPDATE")), //revocationTime,
                  null)); // invalidityTime
            }
          } finally {
            datasource.releaseResources(null, rs);
          }
        }
      } catch (SQLException ex) {
        throw new OperationException(DATABASE_FAILURE,
            datasource.translate(sqlSelectUnrevokedSn, ex).getMessage());
      } finally {
        datasource.releaseResources(ps, null);
      }
    }

    // get list of certificates revoked after the generation of Base FullCRL
    // we check all revoked certificates with LUPDATE field (last update) > THISUPDATE - 1.
    final int numEntries = 1000;

    String coreSql =
        "ID,SN,RR,RT,RIT FROM CERT WHERE ID>? AND CA_ID=? AND REV=1 AND NAFTER>? AND LUPDATE>?";
    String sql = datasource.buildSelectFirstSql(numEntries, "ID ASC", coreSql);
    ps = prepareStatement(sql);
    long startId = 1;

    // -1: so that no entry is ignored: consider all revoked certificates with
    // Database.lastUpdate >= CRL.thisUpdate
    final long updatedSince = crl.getThisUpdate().getDate().getTime() / 1000 - 1;

    try {
      ResultSet rs = null;
      while (true) {
        ps.setLong(1, startId - 1);
        ps.setInt(2, ca.getId());
        ps.setLong(3, notExpiredAt.getTime() / 1000 + 1);
        ps.setLong(4, updatedSince);
        rs = ps.executeQuery();

        try {
          int num = 0;
          while (rs.next()) {
            num++;
            long id = rs.getLong("ID");
            if (id > startId) {
              startId = id;
            }

            BigInteger sn = new BigInteger(rs.getString("SN"), 16);
            if (allSnSet.contains(sn)) {
              // already contained in CRL
              continue;
            }

            long revInvalidityTime = rs.getLong("RIT");
            Date invalidityTime = (revInvalidityTime == 0)
                                    ? null : new Date(1000 * revInvalidityTime);
            CertRevInfoWithSerial revInfo = new CertRevInfoWithSerial(id, sn,
                rs.getInt("RR"), new Date(1000 * rs.getLong("RT")), invalidityTime);
            ret.add(revInfo);
          }

          if (num < numEntries) {
            // no more entries
            break;
          }
        } finally {
          datasource.releaseResources(null,rs);
        }
      }
    } catch (SQLException ex) {
      throw new OperationException(DATABASE_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      datasource.releaseResources(ps, null);
    }

    return ret;
  } // method getCertsForDeltaCrl

  public CertStatus getCertStatusForSubject(NameId ca, X500Name subject) throws OperationException {
    long subjectFp = X509Util.fpCanonicalizedName(subject);
    ResultRow rs = execQuery1PrepStmt0(sqlCertStatusForSubjectFp, col3s(COL3_REV),
                    col2Long(subjectFp), col2Int(ca.getId()));
    return (rs == null) ? CertStatus.UNKNOWN
                        : rs.getBoolean("REV") ? CertStatus.REVOKED : CertStatus.GOOD;
  } // method getCertStatusForSubjectFp

  public boolean isCertForSubjectIssued(NameId ca, long subjectFp) throws OperationException {
    notNull(ca, "ca");

    ResultRow rs = execQuery1PrepStmt0(sqlCertforSubjectIssued, col3s(COL3_ID),
                    col2Int(ca.getId()), col2Long(subjectFp));
    return rs != null;
  }

  public boolean isHealthy() {
    try {
      execUpdateStmt("SELECT ID FROM CA");
      return true;
    } catch (Exception ex) {
      LOG.error("isHealthy(). {}: {}", ex.getClass().getName(), ex.getMessage());
      LOG.debug("isHealthy()", ex);
      return false;
    }
  } // method isHealthy

  public String getLatestSerialNumber(X500Name nameWithSn) throws OperationException {
    RDN[] rdns1 = nameWithSn.getRDNs();
    RDN[] rdns2 = new RDN[rdns1.length];
    for (int i = 0; i < rdns1.length; i++) {
      RDN rdn = rdns1[i];
      rdns2[i] =  rdn.getFirst().getType().equals(ObjectIdentifiers.DN.serialNumber)
          ? new RDN(ObjectIdentifiers.DN.serialNumber, new DERPrintableString("%")) : rdn;
    }

    String namePattern = X509Util.getRfc4519Name(new X500Name(rdns2));

    ResultRow rs = execQuery1PrepStmt0(sqlLatestSerialForSubjectLike, null, col2Str(namePattern));
    if (rs == null) {
      return null;
    }

    X500Name lastName = new X500Name(rs.getString("SUBJECT"));
    RDN[] rdns = lastName.getRDNs(ObjectIdentifiers.DN.serialNumber);
    if (rdns == null || rdns.length == 0) {
      return null;
    }

    return X509Util.rdnValueToString(rdns[0].getFirst().getValue());
  } // method getLatestSerialNumber

  public void deleteUnreferencedRequests() throws OperationException {
    execUpdateStmt0(SQL_DELETE_UNREFERENCED_REQUEST);
  } // method deleteUnreferencedRequests

  public long addRequest(byte[] request) throws OperationException {
    notNull(request, "request");

    long id = idGenerator.nextId();
    long currentTimeSeconds = System.currentTimeMillis() / 1000;
    execUpdatePrepStmt0(SQL_ADD_REQUEST,
        col2Long(id), col2Long(currentTimeSeconds), col2Str(Base64.encodeToString(request)));

    return id;
  } // method addRequest

  public void addRequestCert(long requestId, long certId) throws OperationException {
    long id = idGenerator.nextId();
    execUpdatePrepStmt0(SQL_ADD_REQCERT, col2Long(id), col2Long(requestId), col2Long(certId));
  } // method addRequestCert

  private static Long getDateSeconds(Date date) {
    return date == null ? null : date.getTime() / 1000;
  }

}
