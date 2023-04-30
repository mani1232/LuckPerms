/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.common.model;

import me.lucko.luckperms.common.cache.Cache;
import me.lucko.luckperms.common.cacheddata.result.IntegerResult;
import me.lucko.luckperms.common.config.ConfigKeys;
import me.lucko.luckperms.common.query.QueryOptionsImpl;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.WeightNode;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Locale;
import java.util.Map;

/**
 * Cache instance to supply the weight of a {@link Group}.
 */
public class WeightCache extends Cache<IntegerResult<WeightNode>> {
    private final Group group;

    public WeightCache(Group group) {
        this.group = group;
    }

    @Override
    protected @NonNull IntegerResult<WeightNode> supply() {
        IntegerResult<WeightNode> weight = null;

        for (WeightNode n : this.group.getOwnNodes(NodeType.WEIGHT, QueryOptionsImpl.DEFAULT_NON_CONTEXTUAL)) {
            int value = n.getWeight();
            if (weight == null || value > weight.intResult()) {
                weight = IntegerResult.of(n);
            }
        }

        if (weight == null) {
            Map<String, Integer> configWeights = this.group.getPlugin().getConfiguration().get(ConfigKeys.GROUP_WEIGHTS);
            Integer value = configWeights.get(this.group.getIdentifier().getName().toLowerCase(Locale.ROOT));
            if (value != null) {
                weight = IntegerResult.of(value);
            }
        }

        return weight != null ? weight : IntegerResult.nullResult();
    }
}
