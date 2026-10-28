// DbTx's constructor is private[magnum] in Magnum 1.3.1. Our transactions are owned by dms.db.Db
// (one connection per transaction, shared with Flowable), so Magnum must wrap an existing connection.
// Re-check this bridge on every Magnum upgrade (ADR 0012).
package com.augustnagro.magnum

import java.sql.Connection

object MagnumBridge:
  def dbTx(connection: Connection): DbTx = DbTx(connection, SqlLogger.Default)
