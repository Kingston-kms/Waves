package com.wavesplatform.state.patch

import com.wavesplatform.state.{Blockchain, Diff, LeaseBalance, Portfolio}
import com.wavesplatform.utils.ScorexLogging

object CancelLeaseOverflow extends ScorexLogging {
  def apply(blockchain: Blockchain): Diff = {
    log.info("Cancelling all lease overflows for sender")

    val addressesWithLeaseOverflow = blockchain.collectLposPortfolios {
      case (_, p) if p.balance < p.lease.out => Portfolio(0, LeaseBalance(0, -p.lease.out), Map.empty)
    }

    val addressSet = addressesWithLeaseOverflow.keySet
    addressSet.foreach(addr => log.info(s"Resetting lease overflow for $addr"))

    val leasesToCancel = concurrent.blocking {
      blockchain
        .collectActiveLeases(tx => addressSet(tx.sender.toAddress))
        .map(_.id())
        .toVector
    }

    leasesToCancel.foreach(id => log.info(s"Cancelling lease $id"))
    log.info("Finished cancelling all lease overflows for sender")

    val diff = Diff.empty.copy(portfolios = addressesWithLeaseOverflow, leaseState = leasesToCancel.map(_ -> false).toMap)
    PatchLoader.write("CancelLeaseOverflow", blockchain.height, diff)
    diff
  }
}
