package uk.gov.moj.cp.migration.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ShardTest {

    @Test
    void labelIsAllForTheUnboundedShardAndTheLowerBoundOtherwise() {
        assertThat(new Shard(null, null, null).label()).isEqualTo("all");
        assertThat(new Shard("a", "b", null).label()).isEqualTo("a");
    }

    @Test
    void keysetFilterReturnsNullForTheFirstPage() {
        assertThat(Shard.keysetFilter(null)).isNull();
        assertThat(Shard.keysetFilter("")).isNull();
    }

    @Test
    void keysetFilterOrdersByIdGreaterThanCursor() {
        assertThat(Shard.keysetFilter("id-42")).isEqualTo("id gt 'id-42'");
    }

    @Test
    void keysetFilterEscapesSingleQuotesToPreventFilterBreakage() {
        assertThat(Shard.keysetFilter("O'Brien")).isEqualTo("id gt 'O''Brien'");
    }

    @Test
    void pageFilterComposesShardBoundsWithCursor() {
        assertThat(new Shard(null, null, null).pageFilter(null)).isNull();
        assertThat(new Shard(null, null, null).pageFilter("x")).isEqualTo("id gt 'x'");
        assertThat(new Shard("a", "b", null).pageFilter(null)).isEqualTo("id ge 'a' and id lt 'b'");
        assertThat(new Shard("a", "b", null).pageFilter("a5"))
                .isEqualTo("id ge 'a' and id lt 'b' and id gt 'a5'");
    }

    @Test
    void planReturnsOneFullRangeShardCarryingTheCursorForASingleWorker() {
        assertThat(Shard.plan(1, "cursor-1")).containsExactly(new Shard(null, null, "cursor-1"));
    }

    @Test
    void planIgnoresAndWarnsAboutAResumeCursorWhenShardingAcrossMultipleWorkers() {
        // workers > 1 + a non-empty cursor exercises the "startAfterId ignored" warn branch.
        assertThat(Shard.plan(8, "some-cursor"))
                .hasSize(16)
                .allSatisfy(shard -> assertThat(shard.startCursor()).isNull());
    }

    @Test
    void planPartitionsTheUuidKeySpaceIntoSixteenContiguousHexShardsForMultipleWorkers() {
        final List<Shard> shards = Shard.plan(8, null);

        assertThat(shards).hasSize(16);
        assertThat(shards.get(0)).isEqualTo(new Shard("0", "1", null));
        assertThat(shards.get(9)).isEqualTo(new Shard("9", "a", null)); // hex boundary 9 -> a
        assertThat(shards.get(15)).isEqualTo(new Shard("f", "g", null)); // last bucket closes at 'g'
        for (int i = 0; i < shards.size() - 1; i++) {
            assertThat(shards.get(i).upper()).isEqualTo(shards.get(i + 1).lower()); // contiguous, no gaps
        }
    }
}
