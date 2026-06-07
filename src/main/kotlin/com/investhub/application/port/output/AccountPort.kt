package com.investhub.application.port.output

import com.investhub.domain.account.AssetSummary

interface AccountPort {
    fun getAssetSummary(userId: String): AssetSummary
}
