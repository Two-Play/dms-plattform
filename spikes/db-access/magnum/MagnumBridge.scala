// DbTx/DbCon constructors are private[magnum] in 1.3.1. To manage the transaction
// ourselves (as a ZIO scoped resource) we need to wrap an existing Connection,
// so this one-liner lives in Magnum's package. Finding of the spike, see README.
package com.augustnagro.magnum

import java.sql.Connection

object MagnumBridge:
  def dbTx(con: Connection): DbTx = DbTx(con, SqlLogger.Default)
