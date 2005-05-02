/*
 * Bytecode Analysis Framework
 * Copyright (C) 2003-2005 University of Maryland
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

package edu.umd.cs.findbugs.ba.ch;


import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.bcel.Repository;
import org.apache.bcel.classfile.Constant;
import org.apache.bcel.classfile.ConstantClass;
import org.apache.bcel.classfile.ConstantDouble;
import org.apache.bcel.classfile.ConstantLong;
import org.apache.bcel.classfile.ConstantPool;
import org.apache.bcel.classfile.JavaClass;


public class Subtypes {
	private static final boolean DEBUG_HIERARCHY = Boolean.getBoolean("findbugs.debug.hierarchy");

	boolean computed = false;
	Set<String> referenced = new HashSet<String>();
	Set<JavaClass> handled = new HashSet<JavaClass>();

	Set<JavaClass> parentsAdded = new HashSet<JavaClass>();
	Map<JavaClass,Set<JavaClass>> immediateSubtypes =
		new HashMap<JavaClass,Set<JavaClass>>();
	Map<JavaClass,Set<JavaClass>> transitiveSubtypes =
		new HashMap<JavaClass,Set<JavaClass>>();

	public Set<JavaClass> getImmediateSubtypes(JavaClass c) {
		if (!handled.contains(c)) addClass(c);
		if (!computed) compute();
		return immediateSubtypes.get(c);
		}


	public Set<JavaClass> getTransitiveSubtypes(JavaClass c) {
		if (!handled.contains(c)) addClass(c);
		if (!computed) compute();
		return transitiveSubtypes.get(c);
		}

	public Set<JavaClass> getReferencedClasses(JavaClass c) {
		Set<JavaClass> result = new HashSet<JavaClass>();
		ConstantPool cp = c.getConstantPool();
		Constant[] constants = cp.getConstantPool();
		for(int i = 0; i < constants.length; i++) {
		    Constant co = constants[i];
		    if (co instanceof ConstantDouble 
				|| co instanceof ConstantLong)
			i++;
		    if (co instanceof ConstantClass) {
			String name = ((ConstantClass)co).getBytes(cp);
			if (name.charAt(0) != '[' && referenced.add(name))
			try {
			JavaClass clazz = Repository.lookupClass(name);
			result.add(clazz);
			} catch (ClassNotFoundException e) {
				}
			}
		}
		return result;
		}
		
	public void addApplicationClass(JavaClass c) {
		if (c == null) return;
		if (DEBUG_HIERARCHY) 
		System.out.println("Adding application class " + c.getClassName());
		addClass(c);
		for(JavaClass x : getReferencedClasses(c))
			addClass(x);
		}
	public void addClass(JavaClass c) {
		if (c == null) return;
		if (!handled.add(c)) return;

		if (DEBUG_HIERARCHY) 
		System.out.println("Adding " + c.getClassName());
		Set<JavaClass> children = immediateSubtypes.get(c);
		if (children == null)
			immediateSubtypes.put(c, new HashSet<JavaClass>());
		if (DEBUG_HIERARCHY && computed)
			System.out.println("Need to recompute");
		computed = false;
		}

	public void addParents(JavaClass c) {
		if (!parentsAdded.add(c)) return;
		try {
		addParent(c.getSuperClass(),c);
		for(JavaClass i : c.getInterfaces())
			addParent(i,c);
		} catch (ClassNotFoundException e) {
			if (DEBUG_HIERARCHY)  {
				System.out.println("Error adding parent(s) of " + c.getClassName());
				e.printStackTrace(System.out);
				}
			}
		}
	
	public void addParent(JavaClass p, JavaClass c) {
		if (p == null) return;
		if (DEBUG_HIERARCHY) 
		System.out.println("adding " + c.getClassName()
				+ " is a " + p.getClassName());
		addClass(p);
		addParents(p);
		Set<JavaClass> children = immediateSubtypes.get(p);
		children.add(c);
		}

	public void compute() {
		if (DEBUG_HIERARCHY) 
		System.out.println("Computing {");
		immediateSubtypes.clear();
		transitiveSubtypes.clear();
		parentsAdded.clear();
		for(JavaClass c : handled)  {
			immediateSubtypes.put(c, new HashSet<JavaClass>());
			}
		for(JavaClass c : new HashSet<JavaClass>(handled))  {
			addParents(c);
			}
		for(JavaClass c : handled)  {
			compute(c);
			}
		if (DEBUG_HIERARCHY) 
		System.out.println("} Done Computing");
		computed = true;
		}

	public Set<JavaClass> compute(JavaClass c) {
		if (DEBUG_HIERARCHY) 
			System.out.println(" compute " + c.getClassName()
				+ " : " + immediateSubtypes.get(c).size());
		Set<JavaClass> descendents = transitiveSubtypes.get(c);
		if (descendents != null) {
			if (!descendents.contains(c))
				System.out.println("This is wrong for " + c.getClassName());
			return descendents;
			}
		descendents = new HashSet<JavaClass>();
		descendents.add(c);
		transitiveSubtypes.put(c, descendents);
		for(JavaClass child : immediateSubtypes.get(c)) {
			descendents.add(c);
			descendents.addAll(compute(child));
			}
		if (DEBUG_HIERARCHY) 
		System.out.println(c.getClassName() + " has " + descendents.size()
				+ " decendents");
		return descendents;
		}
	}
		
