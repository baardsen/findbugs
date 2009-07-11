/*
 * Bytecode Analysis Framework
 * Copyright (C) 2003,2004 University of Maryland
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs.ba.vna;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import edu.umd.cs.findbugs.SystemProperties;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.ba.AnalysisContext;
import edu.umd.cs.findbugs.ba.FieldSummary;
import edu.umd.cs.findbugs.ba.Frame;
import edu.umd.cs.findbugs.ba.XField;
import edu.umd.cs.findbugs.util.Strings;
import edu.umd.cs.findbugs.util.Util;

/**
 * A dataflow value representing a Java stack frame with value number
 * information.
 *
 * @author David Hovemeyer
 * @see ValueNumber
 * @see ValueNumberAnalysis
 */
public class ValueNumberFrame extends Frame<ValueNumber> implements ValueNumberAnalysisFeatures {

	private ArrayList<ValueNumber> mergedValueList;
	private Map<AvailableLoad, ValueNumber[]> availableLoadMap;
	private Map<AvailableLoad,ValueNumber> mergedLoads ;
	private Map<ValueNumber, AvailableLoad> previouslyKnownAs;
	public boolean phiNodeForLoads;

	public ValueNumberFrame(int numLocals) {
		super(numLocals);
		if (REDUNDANT_LOAD_ELIMINATION) {
			setAvailableLoadMap(Collections.<AvailableLoad, ValueNumber[]>emptyMap());
			setMergedLoads(Collections.<AvailableLoad, ValueNumber>emptyMap());
			setPreviouslyKnownAs(Collections.<ValueNumber, AvailableLoad>emptyMap());
		}
	}

	public String availableLoadMapAsString() {
		StringBuilder buf = new StringBuilder("{ ");
		for(Map.Entry<AvailableLoad, ValueNumber[]> e : getAvailableLoadMap().entrySet()) {
			buf.append(e.getKey());
			buf.append("=");
			for(ValueNumber v : e.getValue()) 
				buf.append(v).append(",");
			buf.append(";  ");
		}

		buf.append(" }");
		return buf.toString();
	}
	public @CheckForNull AvailableLoad getLoad(ValueNumber v) {
		if (!REDUNDANT_LOAD_ELIMINATION) 
			return null;
		for(Map.Entry<AvailableLoad, ValueNumber[]> e : getAvailableLoadMap().entrySet()) {
			ValueNumber[] values = e.getValue();
			if (values != null)
				for(ValueNumber v2 : values)
					if (v.equals(v2)) 
						return e.getKey();
		}
		return null;
	}
	/**
	 * Look for an available load.
	 *
	 * @param availableLoad the AvailableLoad (reference and field)
	 * @return the value(s) available, or null if no matching entry is found
	 */
	public ValueNumber[] getAvailableLoad(AvailableLoad availableLoad) {
		return getAvailableLoadMap().get(availableLoad);
	}

	/**
	 * Add an available load.
	 *
	 * @param availableLoad the AvailableLoad (reference and field)
	 * @param value         the value(s) loaded
	 */
	public void addAvailableLoad(AvailableLoad availableLoad, @NonNull ValueNumber[] value) {
		if (value == null) throw new IllegalStateException();
		getUpdateableAvailableLoadMap().put(availableLoad, value);

		for(ValueNumber v : value) {
			getUpdateablePreviouslyKnownAs().put(v, availableLoad);
			if (RLE_DEBUG) {
				System.out.println("Adding available load of " + availableLoad + " for " + v + " to " + System.identityHashCode(this));
			}
		}
	}

	
	private static <K,V> void removeAllKeys(Map<K,V> map, Iterable<K> removeMe) {
		for(K k : removeMe)
			map.remove(k);
	}
	/**
	 * Kill all loads of given field.
	 *
	 * @param field the field
	 */
	public void killLoadsOfField(XField field) {
	    if (!REDUNDANT_LOAD_ELIMINATION)  return;
		HashSet<AvailableLoad> killMe = new HashSet<AvailableLoad>();
		for(AvailableLoad availableLoad : getAvailableLoadMap().keySet()) {
			if (availableLoad.getField().equals(field)) {
				if (RLE_DEBUG) 
					System.out.println("KILLING Load of " + availableLoad + " in " + this);
				killMe.add(availableLoad);
			}
		}
		killAvailableLoads(killMe);
	}

	private static boolean USE_WRITTEN_OUTSIDE_OF_CONSTRUCTOR = true;
	/**
	 * Kill all loads.
	 * This conservatively handles method calls where we
	 * don't really know what fields might be assigned.
	 */
	public void killAllLoads() {
		if (!REDUNDANT_LOAD_ELIMINATION)  return;
		FieldSummary fieldSummary = AnalysisContext.currentAnalysisContext().getFieldSummary();
		HashSet<AvailableLoad> killMe = new HashSet<AvailableLoad>();
		for(AvailableLoad availableLoad : getAvailableLoadMap().keySet()) {
			XField field = availableLoad.getField();
			if (field.isVolatile() || !field.isFinal() && (!USE_WRITTEN_OUTSIDE_OF_CONSTRUCTOR || fieldSummary.isWrittenOutsideOfConstructor(field))) {
				if (RLE_DEBUG) 
					System.out.println("KILLING load of " + availableLoad + " in " + this);
				killMe.add(availableLoad);
			}
		}
		killAvailableLoads(killMe);

	}
	public void killAllLoadsExceptFor(@CheckForNull ValueNumber v) {
		if (!REDUNDANT_LOAD_ELIMINATION)  return;
		AvailableLoad myLoad = getLoad(v);
		HashSet<AvailableLoad> killMe = new HashSet<AvailableLoad>();
		for(AvailableLoad availableLoad : getAvailableLoadMap().keySet()) {
			if (!availableLoad.getField().isFinal() && !availableLoad.equals(myLoad)) {
				if (RLE_DEBUG) 
					System.out.println("KILLING load of " + availableLoad + " in " + this);
				killMe.add(availableLoad);
			}
		}
		killAvailableLoads(killMe);
	}
	/**
	 * Kill all loads.
	 * This conservatively handles method calls where we
	 * don't really know what fields might be assigned.
	 */
	public void killAllLoadsOf(@CheckForNull ValueNumber v) {
		if (!REDUNDANT_LOAD_ELIMINATION) return;
		FieldSummary fieldSummary = AnalysisContext.currentAnalysisContext().getFieldSummary();

		HashSet<AvailableLoad> killMe = new HashSet<AvailableLoad>();
		for(AvailableLoad availableLoad : getAvailableLoadMap().keySet()) {
			if (availableLoad.getReference() != v) continue;
			XField field = availableLoad.getField();
			if (!field.isFinal() && (!USE_WRITTEN_OUTSIDE_OF_CONSTRUCTOR || fieldSummary.isWrittenOutsideOfConstructor(field))) {
				if (RLE_DEBUG) System.out.println("Killing load of " + availableLoad + " in " + this);
				killMe.add(availableLoad);
			}
		}
		killAvailableLoads(killMe);

	}

	public void killLoadsOf(Set<XField> fieldsToKill) {
		if (!REDUNDANT_LOAD_ELIMINATION) return;
		HashSet<AvailableLoad> killMe = new HashSet<AvailableLoad>();
		for(AvailableLoad availableLoad : getAvailableLoadMap().keySet()) {

			if (fieldsToKill.contains(availableLoad.getField()) )
				killMe.add(availableLoad);

		}
		killAvailableLoads(killMe);

	}
	public void killLoadsWithSimilarName(String className, String methodName) {
		if (!REDUNDANT_LOAD_ELIMINATION) return;
		String packageName = extractPackageName(className);

		HashSet<AvailableLoad> killMe = new HashSet<AvailableLoad>();
		for(AvailableLoad availableLoad : getAvailableLoadMap().keySet()) {

			XField field = availableLoad.getField();
			String fieldPackageName = extractPackageName(field.getClassName());
			if (packageName.equals(fieldPackageName) && field.isStatic() 
					&& methodName.toLowerCase().indexOf(field.getName().toLowerCase()) >= 0)
				killMe.add(availableLoad);

		}
		killAvailableLoads(killMe);
	}

	/**
     * @param killMe
     */
    private void killAvailableLoads(HashSet<AvailableLoad> killMe) {
	    if (killMe.size() > 0)
			removeAllKeys(getUpdateableAvailableLoadMap(), killMe);
    }


	/**
	 * @param className
	 * @return
	 */
	private String extractPackageName(String className) {
		return className.substring(className.lastIndexOf('.')+1);
	}

	void mergeAvailableLoadSets(ValueNumberFrame other, ValueNumberFactory factory, MergeTree mergeTree) {
		if (REDUNDANT_LOAD_ELIMINATION) {
			// Merge available load sets.
			// Only loads that are available in both frames
			// remain available. All others are discarded.
			String s = "";
			if (RLE_DEBUG) {
				s = "Merging " + this.availableLoadMapAsString() + " and " + other.availableLoadMapAsString();
			}
			boolean changed = false;
			if (other.isBottom()) {
				changed = !this.getAvailableLoadMap().isEmpty();
				setAvailableLoadMap(Collections.<AvailableLoad, ValueNumber[]>emptyMap());
			}
			else if (!other.isTop()) {
				for(Map.Entry<AvailableLoad,ValueNumber[]> e : getUpdateableAvailableLoadMap().entrySet()) {
					AvailableLoad load = e.getKey();
					ValueNumber[] myVN = e.getValue();
					ValueNumber[] otherVN = other.getAvailableLoadMap().get(load);
					if (false && this.phiNodeForLoads && myVN != null && myVN.length == 1 && myVN[0].hasFlag(ValueNumber.PHI_NODE))
						continue;
					if (!Arrays.equals(myVN, otherVN)) {

						ValueNumber phi = getMergedLoads().get(load);
						if (phi == null) {
							int flags = ValueNumber.PHI_NODE;
							for(ValueNumber vn : myVN) {
								flags |= vn.getFlags();
							}
							if (otherVN != null) for(ValueNumber vn : otherVN) {
								flags |= vn.getFlags();
							}
						
							phi = factory.createFreshValue(flags);

							getUpdateableMergedLoads().put(load, phi);
							for(ValueNumber vn : myVN) {
								mergeTree.mapInputToOutput(vn, phi);
							}
							if (otherVN != null) for(ValueNumber vn : otherVN) {
								mergeTree.mapInputToOutput(vn, phi);
							}

							if (RLE_DEBUG)
								System.out.println("Creating phi node " + phi + " for " + load + " from " + Strings.toString(myVN) + " x " +  Strings.toString(otherVN) + " in " + System.identityHashCode(this));	
							changed = true;
							e.setValue(new ValueNumber[] { phi });
						} else {
							if (RLE_DEBUG)
									System.out.println("Reusing phi node : " + phi + " for " + load + " from "+ Strings.toString(myVN) + " x " +  Strings.toString(otherVN)+ " in " + System.identityHashCode(this));
							if (myVN.length != 1 || !myVN[0].equals(phi))
								e.setValue(new ValueNumber[] { phi });
						}

					}

				}	
			}
			Map<ValueNumber, AvailableLoad> previouslyKnownAsOther = other.getPreviouslyKnownAs();
			if (getPreviouslyKnownAs() != previouslyKnownAsOther 
					&& previouslyKnownAsOther.size() != 0) {
				if (getPreviouslyKnownAs().size() == 0) 
					assignPreviouslyKnownAs(other);
				else getUpdateablePreviouslyKnownAs().putAll(previouslyKnownAsOther);
			}
			if (changed)
				this.phiNodeForLoads = true;
			if (changed && RLE_DEBUG) {
				System.out.println(s);
				System.out.println("  Result is " + this.availableLoadMapAsString());
				System.out.println(" Set phi for " + System.identityHashCode(this));
			}
		}
	}


	ValueNumber getMergedValue(int slot) {
		return mergedValueList.get(slot);
	}

	void setMergedValue(int slot, ValueNumber value) {
		mergedValueList.set(slot, value);
	}

	  @Override
	public void copyFrom(Frame<ValueNumber> other) {
		if (!(other instanceof ValueNumberFrame)) throw new IllegalArgumentException();
		// If merged value list hasn't been created yet, create it.
		if (mergedValueList == null && other.isValid()) {
			// This is where this frame gets its size.
			// It will have the same size as long as it remains valid.
			mergedValueList = new ArrayList<ValueNumber>();
			int numSlots = other.getNumSlots();
			for (int i = 0; i < numSlots; ++i)
				mergedValueList.add(null);
		}

		if (REDUNDANT_LOAD_ELIMINATION) {
			assignAvailableLoadMap((ValueNumberFrame) other);
			assignPreviouslyKnownAs((ValueNumberFrame) other);
		}

		super.copyFrom(other);
	}

	  private void assignAvailableLoadMap(ValueNumberFrame other) {
			Map<AvailableLoad, ValueNumber[]> availableLoadMapOther = other.getAvailableLoadMap();
			if (availableLoadMapOther instanceof HashMap) {
				availableLoadMapOther = Collections.<AvailableLoad, ValueNumber[]>unmodifiableMap(availableLoadMapOther);
				other.setAvailableLoadMap(availableLoadMapOther);
				setAvailableLoadMap(availableLoadMapOther);   
				constructedUnmodifiableMap++;
			} else {
				setAvailableLoadMap(availableLoadMapOther);
				reusedMap++;
			}
		}

	private void assignPreviouslyKnownAs(ValueNumberFrame other) {
		Map<ValueNumber, AvailableLoad> previouslyKnownAsOther = other.getPreviouslyKnownAs();
		if (previouslyKnownAsOther instanceof HashMap) {
			previouslyKnownAsOther = Collections.<ValueNumber, AvailableLoad>unmodifiableMap(previouslyKnownAsOther);
			other.setPreviouslyKnownAs(previouslyKnownAsOther);
			setPreviouslyKnownAs(previouslyKnownAsOther);   
			constructedUnmodifiableMap++;
		} else {
			setPreviouslyKnownAs(previouslyKnownAsOther);
			reusedMap++;
		}
	}



	static int constructedUnmodifiableMap;
	static int reusedMap;
	@Override
	public String toString() {
		String frameValues = super.toString();
		if (RLE_DEBUG) {
			StringBuilder buf = new StringBuilder();
			buf.append(frameValues);

			Iterator<AvailableLoad> i = getAvailableLoadMap().keySet().iterator();
			boolean first = true;
			while (i.hasNext()) {
				AvailableLoad key = i.next();
				ValueNumber[] value = getAvailableLoadMap().get(key);
				if (first)
					first = false;
				else
					buf.append(',');
				buf.append(key + "=" + valueToString(value));
			}

			buf.append(" #");
			buf.append(System.identityHashCode(this));
			if (phiNodeForLoads) buf.append(" phi");
			return buf.toString();
		} else {
			return frameValues;
		}
	}

	private static String valueToString(ValueNumber[] valueNumberList) {
		StringBuilder buf = new StringBuilder();
		buf.append('[');
		boolean first = true;
		for (ValueNumber aValueNumberList : valueNumberList) {
			if (first)
				first = false;
			else
				buf.append(',');
			buf.append(aValueNumberList.getNumber());
		}
		buf.append(']');
		return buf.toString();
	}

	public boolean fuzzyMatch(ValueNumber v1, ValueNumber v2) {
		if (REDUNDANT_LOAD_ELIMINATION)
		  return v1.equals(v2) || fromMatchingLoads(v1, v2) || haveMatchingFlags(v1, v2); 
		else
		  return v1.equals(v2);
	}

	public boolean veryFuzzyMatch(ValueNumber v1, ValueNumber v2) {
		if (REDUNDANT_LOAD_ELIMINATION)
		  return v1.equals(v2) || fromMatchingFields(v1, v2) || haveMatchingFlags(v1, v2); 
		else
		  return v1.equals(v2);
	}
	public boolean fromMatchingLoads(ValueNumber v1, ValueNumber v2) {
		AvailableLoad load1 = getLoad(v1);
		if (load1 == null) load1 = getPreviouslyKnownAs().get(v1);
		if (load1 == null) return false;
		AvailableLoad load2 = getLoad(v2);
		if (load2 == null) load2 = getPreviouslyKnownAs().get(v2);
		if (load2 == null) return false;
		return load1.equals(load2);
	}

	public boolean fromMatchingFields(ValueNumber v1, ValueNumber v2) {
		AvailableLoad load1 = getLoad(v1);
		if (load1 == null) load1 = getPreviouslyKnownAs().get(v1);
		if (load1 == null) return false;
		AvailableLoad load2 = getLoad(v2);
		if (load2 == null) load2 = getPreviouslyKnownAs().get(v2);
		if (load2 == null) return false;
		if (load1.equals(load2)) return true;
		if (load1.getField().equals(load2.getField())) {
			ValueNumber source1 = load1.getReference();
			ValueNumber source2 = load2.getReference();
			if (!this.contains(source1)) return true;
			if (!this.contains(source2)) return true;
		}
		return false;
	}
	/**
	 * @param v1
	 * @param v2
	 * @return true if v1 and v2 have a flag in common
	 */
	public boolean haveMatchingFlags(ValueNumber v1, ValueNumber v2) {
		int flag1 = v1.getFlags();
		int flag2 = v2.getFlags();
		return (flag1 & flag2) != 0;
	}

	public Collection<ValueNumber> valueNumbersForLoads() {
		HashSet<ValueNumber> result = new HashSet<ValueNumber>();
		if (REDUNDANT_LOAD_ELIMINATION)
		for(Map.Entry<AvailableLoad, ValueNumber[]> e : getAvailableLoadMap().entrySet()) {
			if (e.getValue() != null)
				for(ValueNumber v2 : e.getValue())
					result.add(v2);
		}

		return result;
	}

	/**
	 * @param availableLoadMap The availableLoadMap to set.
	 */
	private void setAvailableLoadMap(Map<AvailableLoad, ValueNumber[]> availableLoadMap) {
		this.availableLoadMap = availableLoadMap;
	}

	/**
	 * @return Returns the availableLoadMap.
	 */
	private Map<AvailableLoad, ValueNumber[]> getAvailableLoadMap() {
		return availableLoadMap;
	}
	private Map<AvailableLoad, ValueNumber[]> getUpdateableAvailableLoadMap() {
		if (!(availableLoadMap instanceof HashMap)) {
			HashMap<AvailableLoad, ValueNumber[]> tmp =  new HashMap<AvailableLoad, ValueNumber[]>(availableLoadMap.size()+4);
			tmp.putAll(availableLoadMap);
			availableLoadMap = tmp;
		}
		return availableLoadMap;
	}
	/**
	 * @param mergedLoads The mergedLoads to set.
	 */
	private void setMergedLoads(Map<AvailableLoad,ValueNumber> mergedLoads) {
		this.mergedLoads = mergedLoads;
	}

	/**
	 * @return Returns the mergedLoads.
	 */
	private Map<AvailableLoad,ValueNumber> getMergedLoads() {
		return mergedLoads;
	}
	private Map<AvailableLoad,ValueNumber> getUpdateableMergedLoads() {
		if (!(mergedLoads instanceof HashMap))
			mergedLoads = new HashMap<AvailableLoad, ValueNumber>();

		return mergedLoads;
	}

	/**
	 * @param previouslyKnownAs The previouslyKnownAs to set.
	 */
	private void setPreviouslyKnownAs(Map<ValueNumber, AvailableLoad> previouslyKnownAs) {
		this.previouslyKnownAs = previouslyKnownAs;
	}

	/**
	 * @return Returns the previouslyKnownAs.
	 */
	private Map<ValueNumber, AvailableLoad> getPreviouslyKnownAs() {
		return previouslyKnownAs;
	}
	static int createdEmptyMap;
	static int madeImmutableMutable;
	static int reusedMutableMap;
	static {
		if(SystemProperties.getBoolean("findbugs.shutdownLogging"))
		Util.runLogAtShutdown(new Runnable() {
			public void run() {
			   System.err.println("Getting updatable previously known as:");
			   System.err.println("  " + createdEmptyMap + " created empty map");
			   System.err.println("  " + madeImmutableMutable + " made immutable map mutable");
			   System.err.println("  " + reusedMutableMap + " reused mutable map");
			   System.err.println("Copying map:");
			   System.err.println("  " + constructedUnmodifiableMap + " made mutable map unmodifiable");
			   System.err.println("  " + reusedMap + " reused immutable map");
			   System.err.println();
			}});
	}
	private Map<ValueNumber, AvailableLoad> getUpdateablePreviouslyKnownAs() {
		if (previouslyKnownAs.size() == 0) {
			previouslyKnownAs = new HashMap<ValueNumber, AvailableLoad>(4);
			createdEmptyMap++;
		}
		else if (!(previouslyKnownAs instanceof HashMap)) {
			HashMap<ValueNumber, AvailableLoad> tmp = new HashMap<ValueNumber, AvailableLoad>(previouslyKnownAs.size()+4);
			tmp.putAll(previouslyKnownAs);
			previouslyKnownAs = tmp;
			madeImmutableMutable++;
		} else
			reusedMutableMap++;

		return previouslyKnownAs;
	}
	

}

// vim:ts=4
