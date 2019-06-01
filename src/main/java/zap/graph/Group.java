package zap.graph;

import gnu.trove.map.hash.TObjectIntHashMap;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import zap.graph.traverse.BFSearcher;
import zap.graph.traverse.INodeContainer;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;
import java.util.function.Consumer;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class Group<C extends IConnectable, N extends IConnectable> implements INodeContainer {
	HashMap<BlockPos, Connectivity.Cache<N>> nodes;
	private HashMap<BlockPos, UUID> connectorPairing;
	HashMap<UUID, Grid<C>> grids;

	// Prevent the creation of empty graph.groups externally, a caller needs to use singleNode/singleConnector.
	// TODO: Make this private, by fixing up Graph.
	Group() {
		nodes = new HashMap<>();
		connectorPairing = new HashMap<>();
		grids = new HashMap<>();
	}

	public static <N extends IConnectable> Group<?, N> singleNode(BlockPos at, N node) {
		Group<?, N> group = new Group<>();

		group.addNode(at, node);

		return group;
	}

	public static <C extends IConnectable> Group<C, ?> singleConnector(BlockPos at, C connector) {
		Group<C, ?> group = new Group<>();
		UUID id = group.getNewId();

		group.connectorPairing.put(at, id);
		group.grids.put(id, Grid.singleConnector(at, connector));

		return group;
	}

	public int countBlocks() {
		return nodes.size() + connectorPairing.size();
	}

	@Override
	public boolean contains(BlockPos at) {
		Objects.requireNonNull(at);

		return nodes.containsKey(at) || connectorPairing.containsKey(at);
	}

	// TODO: The Graph may add/remove entries that do not connect at the time of the method call.
	// TODO: This should be avoided if at all possible.
	public void addEntry(BlockPos at, Entry<C, N> entry) {
		entry.apply(
				connector -> addConnector(at, connector),
				node -> addNode(at, node)
		);
	}

	public void addNode(BlockPos at, N node) {
		Connectivity.Cache<N> cache = Connectivity.Cache.of(Objects.requireNonNull(node));
		nodes.put(Objects.requireNonNull(at), cache);
	}

	public void addConnector(BlockPos at, C connector) {
		Connectivity.Cache<C> cache = Connectivity.Cache.of(Objects.requireNonNull(connector));
		HashMap<UUID, Grid<C>> linkedGrids = new HashMap<>();

		for(EnumFacing facing: EnumFacing.VALUES) {
			if(cache.connects(facing)) {
				BlockPos offset = at.offset(facing);
				UUID id = connectorPairing.get(offset);

				if(id != null) {
					Grid<C> grid = grids.get(id);

					if(grid.connects(offset, facing.getOpposite())) {
						linkedGrids.put(id, grid);
					}
				}
			}
		}

		if(linkedGrids.isEmpty()) {
			// Single connector grid
			UUID id = getNewId();

			connectorPairing.put(at, id);
			grids.put(id, Grid.singleConnector(at, connector));
		} else if(linkedGrids.size() == 1) {
			// Add to existing grid

			Map.Entry<UUID, Grid<C>> entry = linkedGrids.entrySet().iterator().next();

			connectorPairing.put(at, entry.getKey());
			entry.getValue().addConnector(at, cache);
		} else {
			throw new UnsupportedOperationException("Cannot merge grids yet");
		}
	}

	/**
	 * Removes an entry from the Group, potentially splitting it if needed. By calling this function, the caller asserts
	 * that this group contains the specified position; the function may misbehave if the group does not actually contain
	 * the specified position.
	 *
	 * @param pos The position of the entry to remove.
	 * @param split A consumer for the resulting fresh graphs from the split operation.
	 * @return The removed entry, guaranteed to not be null.
	 */
	public Entry<C, N> remove(BlockPos pos, Consumer<Group<C, N>> split) {
		// The contains() check can be skipped here, because Graph will only call remove() if it knows that the group contains the entry.
		// For now, it is retained for completeness and debugging purposes.
		if(!contains(pos)) {
			throw new IllegalArgumentException("Tried to call Group::remove with a position that does not exist within the group.");
		}

		// If removing the entry would not cause a group split, then it is safe to remove the entry directly.
		if(isExternal(pos)) {
			Connectivity.Cache<N> node = nodes.remove(pos);
			UUID pairing = connectorPairing.remove(pos);

			if(node != null) {
				return Entry.node(node.value());
			}

			Grid<C> grid = grids.get(Objects.requireNonNull(pairing));

			// Avoid leaving empty grids within the grid list.
			if(grid.connectors.size() == 0) {
				grids.remove(pairing);
			}

			// No check is needed here, because the caller already asserts that the Group contains the specified position.
			// Thus, if this is not a node, then it is guaranteed to be a connector.
			return Entry.connector(grid.connectors.remove(pos).value());
		}

		// If none of the fast routes work, we need to due a full group-traversal to figure out how the graph will be split.
		// The algorithm works by "coloring" each fragment of the group based on what it is connected to, and then from this,
		// splitting each colored portion into its own separate this.
		// For optimization purposes, the largest colored fragment remains resident within its original this.

		// Note: we don't remove the node yet, but instead just tell the Searcher that it does not exist.
		// This is so that we can handle the grid splits ourselves at the end.
		BFSearcher searcher = new BFSearcher (
				// TODO: This doesn't support multiple removal operations
				at -> (!pos.equals(at)) && (nodes.containsKey(at) || connectorPairing.containsKey(at))
		);

		// Record what sides the search will occur on.
		// In the future, this will enable multiple removals at the same time, such as with chunk unloads.
		HashSet<BlockPos> toSearch = new HashSet<>();
		for(EnumFacing facing: EnumFacing.VALUES) {
			BlockPos side = pos.offset(facing);

			if(this.contains(side)) {
				toSearch.add(side);
			}
		}

		TObjectIntHashMap<BlockPos> roots = new TObjectIntHashMap<>(6, 0.5F, Integer.MAX_VALUE);
		ArrayList<HashSet<BlockPos>> colored = new ArrayList<>();

		for(BlockPos root: toSearch) {
			// Check if this root has already been colored.
			int existingColor = roots.get(root);

			if(existingColor != roots.getNoEntryValue()) {
				// Already colored! No point in doing it again.
				continue;
			}

			final int color = colored.size();
			roots.put(root, color);

			HashSet<BlockPos> found = new HashSet<>();

			searcher.search(root, reached -> {
				if(toSearch.contains(reached)) {
					roots.put(reached, color);
				}

				found.add(reached);
			});

			colored.add(found);
		}

		/// Then, determine which color has the most blocks, in order to avoid unnecessary movement during the split process.
		int best = 0;
		int bestCount = 0;
		for(int i = 0; i < colored.size(); i++) {
			HashSet<BlockPos> found = colored.get(i);

			if(found.size() > bestCount) {
				bestCount = found.size();
				best = i;
			}
		}

		final int bestColor = best;

		// TODO: This doesn't support multiple removal operations
		UUID centerGridId = connectorPairing.get(pos);
		Grid<C> centerGrid = null;
		Entry<C, N> result = null;

		if(centerGridId != null) {
			centerGrid = grids.remove(centerGridId);

			for(BlockPos toMove: centerGrid.connectors.keySet()) {
				connectorPairing.remove(toMove);
			}

			// TODO: Split the central grid.
			throw new UnsupportedOperationException("Cannot split grids on removal operations yet");
		} else {
			result = Entry.node(nodes.remove(pos).value());
		}

		// TODO: Actually remove the grid at the center.
		for(int i = 0; i < colored.size(); i++) {
			if(i == bestColor) {
				// These nodes will be kept.
				continue;
			}

			Group<C, N> newGroup = new Group<>();
			HashSet<BlockPos> found = colored.get(i);

			for(BlockPos reached: found) {
				if(newGroup.connectorPairing.containsKey(reached) || (centerGrid != null && centerGrid.contains(reached))) {
					continue;
				}

				UUID gridId = connectorPairing.get(reached);

				// Just a node then, simply add it to the new group.
				// The maps are mutated directly here in order to retain the cached connectivity.
				if(gridId == null) {
					System.out.println("Moving node at "+reached+" to new group");
					newGroup.nodes.put(reached, Objects.requireNonNull(this.nodes.remove(reached)));
					continue;
				}

				Grid<C> grid = grids.get(gridId);
				if(grid.contains(pos)) {
					// This should be unreachable
					throw new IllegalStateException("Searchable grid contains the removed position, the grid should have been removed already?!?");
				}

				System.out.println("Moving grid "+gridId+" to new group");
				grids.remove(gridId);
				newGroup.grids.put(gridId, grid);

				for(BlockPos moved: grid.connectors.keySet()) {
					connectorPairing.remove(moved);
					newGroup.connectorPairing.put(moved, gridId);
				}
			}

			split.accept(newGroup);
		}

		// TODO: Remove from Grid if needed
		return Objects.requireNonNull(result);
	}

	/**
	 * Tests if a particular position is only connected to the group on a single side, or is the only entry in the group.
	 * @param pos The position to test
	 * @return Whether the position only has a single neighbor in the group, or is the only entry in the group.
	 */
	private boolean isExternal(BlockPos pos) {
		// If the group contains less than 2 blocks, neighbors cannot exist.
		if(this.countBlocks() <= 1) {
			return true;
		}

		int neighbors = 0;
		for(EnumFacing facing: EnumFacing.VALUES) {
			BlockPos face = pos.offset(facing);

			if(this.contains(face)) {
				neighbors += 1;
			}
		}

		return neighbors <= 1;
	}

	// Graph controlled interface
	void mergeWith(Group<C, N> other, BlockPos at) {
		nodes.putAll(other.nodes);

		if(connectorPairing.containsKey(at)) {
			// TODO connectors.putAll(other.connectors);
			throw new UnsupportedOperationException("Cannot mergeWith on a potential grid boundary yet");
		} else {
			// TODO: UUIDs may be colliding, super rare chance...
			grids.putAll(other.grids);
			connectorPairing.putAll(other.connectorPairing);
		}
	}

	private UUID getNewId() {
		UUID uuid = UUID.randomUUID();
		while(grids.containsKey(uuid)) {
			// Should never be called, but whatever.
			uuid = UUID.randomUUID();
		}

		return uuid;
	}
}