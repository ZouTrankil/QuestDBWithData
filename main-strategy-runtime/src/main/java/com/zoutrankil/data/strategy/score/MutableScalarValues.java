package com.zoutrankil.data.strategy.score;

import java.util.*;
import java.util.function.BiConsumer;

/** Mutable scoring cells with shared column layouts and insertion-ordered Map semantics. */
final class MutableScalarValues extends AbstractMap<String,Object> {
    private FrozenScalarValues.Layout layout;
    private Object[] cells;
    private int modifications;

    MutableScalarValues(Map<String,?> source){
        Objects.requireNonNull(source);layout=FrozenScalarValues.layoutOf(source);
        cells=new Object[Math.max(8,layout.size())];FrozenScalarValues.copyCells(source,layout,cells);
    }
    FrozenScalarValues immutableCopy(){return FrozenScalarValues.snapshot(layout,cells);}
    @Override public int size(){return layout.size();}
    @Override public boolean containsKey(Object key){return key!=null&&layout.index(key)>=0;}
    @Override public Object get(Object key){if(key==null)return null;int index=layout.index(key);return index<0?null:cells[index];}
    @Override public Object put(String key,Object value){
        Objects.requireNonNull(key);int index=layout.index(key);
        if(index>=0){Object previous=cells[index];cells[index]=value;return previous;}
        int size=size();
        if(size==cells.length)cells=Arrays.copyOf(cells,Math.max(size+1,size+size/2+1));
        layout=layout.adding(key);cells[size]=value;modifications++;return null;
    }
    @Override public Object remove(Object key){
        if(key==null)return null;int index=layout.index(key);if(index<0)return null;
        Object previous=cells[index];int size=size();
        System.arraycopy(cells,index+1,cells,index,size-index-1);cells[size-1]=null;
        layout=layout.removing(index);modifications++;return previous;
    }
    @Override public void clear(){layout=FrozenScalarValues.layout(List.of());cells=new Object[8];modifications++;}
    @Override public void forEach(BiConsumer<? super String,? super Object> action){
        Objects.requireNonNull(action);int expected=modifications;
        for(int i=0;i<size();i++){action.accept(layout.column(i),cells[i]);if(expected!=modifications)throw new ConcurrentModificationException();}
    }
    @Override public Set<Map.Entry<String,Object>> entrySet(){return new AbstractSet<>() {
        @Override public int size(){return MutableScalarValues.this.size();}
        @Override public void clear(){MutableScalarValues.this.clear();}
        @Override public Iterator<Map.Entry<String,Object>> iterator(){return new Iterator<>() {
            private int next,last=-1,expected=modifications;
            private void requireStable(){if(expected!=modifications)throw new ConcurrentModificationException();}
            @Override public boolean hasNext(){return next<MutableScalarValues.this.size();}
            @Override public Map.Entry<String,Object> next(){
                requireStable();if(!hasNext())throw new NoSuchElementException();last=next;
                String key=layout.column(next++);
                return new Map.Entry<>() {
                    @Override public String getKey(){return key;}
                    @Override public Object getValue(){return MutableScalarValues.this.get(key);}
                    @Override public Object setValue(Object value){return MutableScalarValues.this.put(key,value);}
                    @Override public boolean equals(Object other){return other instanceof Map.Entry<?,?> entry&&Objects.equals(key,entry.getKey())&&Objects.equals(getValue(),entry.getValue());}
                    @Override public int hashCode(){return Objects.hashCode(key)^Objects.hashCode(getValue());}
                };
            }
            @Override public void remove(){
                requireStable();if(last<0)throw new IllegalStateException();
                MutableScalarValues.this.remove(layout.column(last));next=last;last=-1;expected=modifications;
            }
        };}
    };}
}
