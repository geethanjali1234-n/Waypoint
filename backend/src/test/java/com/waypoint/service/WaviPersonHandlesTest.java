package com.waypoint.service;

import com.waypoint.service.WaviPersonHandles.Person;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OFFLINE unit tests of the privacy-preserving person references: names become opaque handles before anything is sent,
 * handles live in one actor-and-session table, and the server swaps names back only against the CURRENT scope.
 * Synthetic names only.
 */
class WaviPersonHandlesTest {

    private static final Person ASHA = new Person("u-asha", "Asha Rao");
    private static final Person RAVI = new Person("u-ravi", "Ravi Teja");
    private static final Person PRIYA = new Person(null, "Priya Nair");

    private final WaviPersonHandles.Registry registry = new WaviPersonHandles.Registry(Duration.ofHours(1), 100);

    private WaviPersonHandles handles(String actor, String session, Person... people) {
        return new WaviPersonHandles(registry.table(actor, session, 1_000L), List.of(people));
    }

    @Test
    void fullNamesBecomeStableHandlesAndNothingElseChanges() {
        var handles = handles("hr", "s1", ASHA, RAVI, PRIYA);
        String hidden = handles.hide("Asha Rao: 1 timesheet; Ravi Teja: 2 timesheets; manager Priya Nair.");
        assertThat(hidden).isEqualTo("[P1]: 1 timesheet; [P2]: 2 timesheets; manager [P3].");
        // The same person keeps the same handle in a later text of the same session.
        assertThat(handles.hide("Ravi Teja again")).isEqualTo("[P2] again");
        assertThat(handles.containsKnownName(hidden)).isFalse();
    }

    @Test
    void hidingIsCaseInsensitiveHandlesPossessivesAndLeavesOtherWordsAlone() {
        var handles = handles("hr", "s1", RAVI);
        assertThat(handles.hide("show ravi teja's timesheets, RAVI TEJA")).isEqualTo("show [P1]'s timesheets, [P1]");
        assertThat(handles.hide("Raviteja is not Ravi Tejas")).isEqualTo("Raviteja is not Ravi Tejas");
    }

    @Test
    void aTypedNamePartIsHiddenOnlyWhenItBelongsToOnePersonAndIsCapitalisedMidSentence() {
        var handles = handles("hr", "s1", ASHA, RAVI, new Person("u-will", "Will Smith"));
        assertThat(handles.hideMessage("show me Ravi's timesheets")).isEqualTo("show me [P1]'s timesheets");
        // A common word that happens to be a name part is not touched, and neither is a name at the very start.
        assertThat(handles.hideMessage("will you show me Asha Rao's hours")).startsWith("will you show me [P").doesNotContain("Asha");
        assertThat(handles.hideMessage("Ravi, please show hours")).startsWith("Ravi,");
    }

    @Test
    void aHandleOnlyResolvesInTheSessionAndForTheActorThatWasIssuedIt() {
        var mine = handles("hr", "s1", ASHA);
        String hidden = mine.hide("Asha Rao");
        assertThat(hidden).isEqualTo("[P1]");
        assertThat(mine.reveal(hidden, List.of(ASHA)).text()).isEqualTo("Asha Rao");
        // Another session of the same user, and another user's session, have their own tables: the handle means nothing there.
        var otherSession = handles("hr", "s2", RAVI);
        var otherActor = handles("someone-else", "s1", RAVI);
        // Even when the person is visible in the other table's scope, a handle issued elsewhere does not resolve.
        assertThat(otherSession.reveal("[P1]", List.of(ASHA, RAVI)).unresolved()).containsExactly("P1");
        assertThat(otherSession.reveal("[P1]", List.of(ASHA)).unresolved()).containsExactly("P1");
        assertThat(otherActor.reveal("[P1]", List.of(ASHA)).unresolved()).containsExactly("P1");
    }

    @Test
    void aForgedOrNeverIssuedHandleNeverResolvesToAnyone() {
        var handles = handles("hr", "s1", ASHA, RAVI);
        handles.hide("Asha Rao");
        var revealed = handles.reveal("[P7] and [P9999] and [P1]", List.of(ASHA, RAVI));
        assertThat(revealed.unresolved()).containsExactlyInAnyOrder("P7", "P9999");
        assertThat(revealed.text()).contains("[P7]", "[P9999]", "Asha Rao");
    }

    @Test
    void revealReChecksTheCurrentScopeAndUsesTheCurrentName() {
        var handles = handles("hr", "s1", ASHA, RAVI);
        String hidden = handles.hide("Asha Rao and Ravi Teja");
        // Ravi has left the reviewer's scope since the handles were issued; Asha was renamed.
        var revealed = handles.reveal(hidden, List.of(new Person("u-asha", "Asha Rao-Menon")));
        assertThat(revealed.unresolved()).containsExactly("P2");
        assertThat(revealed.text()).startsWith("Asha Rao-Menon and ").contains("[P2]").doesNotContain("Ravi");
    }

    @Test
    void aNameSeenOnlyAsTextRevealsFromWhatTheSessionAlreadySaw() {
        var handles = handles("emp", "s1", PRIYA);
        String hidden = handles.hide("Your manager is Priya Nair.");
        assertThat(hidden).isEqualTo("Your manager is [P1].");
        assertThat(handles.reveal(hidden, List.of()).text()).isEqualTo("Your manager is Priya Nair.");
    }

    @Test
    void aPersonWhoLeftTheScopeIsStillHiddenInLaterHistory() {
        var first = handles("hr", "s1", ASHA, RAVI);
        first.hide("Ravi Teja");
        // Later in the session Ravi is no longer visible, but an earlier answer in history still names him.
        var later = handles("hr", "s1", ASHA);
        assertThat(later.hide("Earlier you saw Ravi Teja.")).isEqualTo("Earlier you saw [P1].");
        assertThat(later.knownNames()).contains("ravi teja");
    }

    @Test
    void handlesInATextAreCountedAsDistinctPeople() {
        assertThat(WaviPersonHandles.handlesIn("[P1] and [P2] and [P1] again [Pxyz] [P12345]")).containsExactly("P1", "P2");
    }

    @Test
    void tablesExpireAndAreBounded() {
        var small = new WaviPersonHandles.Registry(Duration.ofMillis(100), 2);
        var table = small.table("a", "s", 0L);
        table.handleFor(ASHA);
        assertThat(small.table("a", "s", 50L)).isSameAs(table);
        // After the time to live a new table starts: old handles are gone.
        assertThat(small.table("a", "s", 1_000L)).isNotSameAs(table);
        small.table("b", "s", 1_000L);
        small.table("c", "s", 1_000L);
        assertThat(small.table("a", "s", 1_000L).keyFor("P1")).isNull();
    }
}
