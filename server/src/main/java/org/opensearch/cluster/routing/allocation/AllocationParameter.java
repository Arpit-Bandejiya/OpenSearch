/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.routing.allocation;

/**
 * RebalanceConstraint Params
 */
public class AllocationParameter {
    private final float preferPrimaryBalanceBuffer;

    public AllocationParameter(float preferPrimaryBalanceBuffer) {
        this.preferPrimaryBalanceBuffer = preferPrimaryBalanceBuffer;
    }

    public float getPreferPrimaryBalanceBuffer() {
        return preferPrimaryBalanceBuffer;
    }
}
