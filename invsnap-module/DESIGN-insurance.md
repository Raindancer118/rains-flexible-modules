## Item insurance — design

**Goal.** A player pays to protect one unstackable item (tool, weapon, armour, elytra, trident) against being
destroyed or lost. It is bought with a premium, renewed on a period, and ends when unpaid, cancelled or worn out.

**What is covered, and what is not.** Dropping, lending and storing are allowed: an insured item lies on the
ground like any other and anyone may pick it up. The policy covers (a) the item entity being destroyed, and
(b) death. Wear is not covered. Cancelling the policy and then throwing the item into lava is how to really
get rid of one.

**Why destruction, not drops.** Refusing drops would break chests, dispensers, lending and every other plugin
that legitimately spawns items. Destruction is the one moment an item is truly lost, so that is where we act:
despawn (`ItemDespawnEvent`), any damage to the item entity (`EntityDamageEvent`: fire, lava, cactus, blasts,
lightning; we treat any damage as destruction rather than guess at the entity's health), and removal from the
world (`EntityRemoveEvent` causes DEATH, DESPAWN, EXPLODE, OUT_OF_WORLD, which catches the void). The stack
goes to its owner: into their inventory if online with room, otherwise onto a persistent "to collect" list.

**Exactly once (duplication).** The stack is secured first (inventory, or written to disk), the entity is removed
second. A damage event is typically followed by a removal event for the same entity; the entity UUID is
remembered, so the second report does nothing. An item that cannot be secured (cannot be encoded, disk write
fails) is not claimed: the entity is left alone, never both returned and left in the world, never gone.
Handing over the list writes the shortened list before the inventory is touched.

**Owner's death.** The stack leaves the drop list and is written to the to-collect file immediately, so a crash
cannot lose it; it is delivered after respawn and the deductible is charged (if unpayable the item still comes
back). With the inventory kept (death insurance, gamerule) it stays where it is and is only removed from the drops.

**Lending and theft.** The policy belongs to the owner, not the holder. Whoever dies with the item, it goes to the
owner's list (and at once into their inventory if online with room); the holder pays nothing, only the owner's
own death charges the deductible. A thief gains nothing the item's owner cannot undo: Core's `InsuredItems` makes
shops and auctions refuse it, and cancelling is the owner's only.

**Wear.** `PlayerItemBreakEvent` ends the policy: insurance covers loss, not wear, otherwise an insured pickaxe
would be a free durability warranty. `/repair` is for that, and the screen says so.

**Lapse.** Premiums are taken by a minute-poll, priced from the value stored when insured (the item may be in a
chest). A premium that cannot be paid ends the policy and the owner is told on next join. With no economy
answering, nothing lapses until it does. A lapsed or cancelled policy's mark is not hunted down:
`InsuredItems.isInsured` ignores dead policies and the mark is stripped when the owner's inventory is next seen.
Switching the feature off makes policies inert (nothing protected, nothing charged) without deleting them.

**Known limits.** Items inside a shulker box are not scanned. An insured item sitting in a chest that is blown
up spawns as an entity first and is then covered like any other. Items destroyed without an entity (a full
hopper, a cleared container by another plugin) are not covered.
