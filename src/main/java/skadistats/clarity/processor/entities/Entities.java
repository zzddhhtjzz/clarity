package skadistats.clarity.processor.entities;

import com.google.protobuf.ByteString;
import skadistats.clarity.decoder.BitStream;
import skadistats.clarity.decoder.FieldReader;
import skadistats.clarity.decoder.Util;
import skadistats.clarity.event.*;
import skadistats.clarity.model.*;
import skadistats.clarity.processor.reader.OnMessage;
import skadistats.clarity.processor.reader.OnReset;
import skadistats.clarity.processor.reader.ResetPhase;
import skadistats.clarity.processor.runner.Context;
import skadistats.clarity.processor.sendtables.DTClasses;
import skadistats.clarity.processor.sendtables.UsesDTClasses;
import skadistats.clarity.processor.stringtables.OnStringTableEntry;
import skadistats.clarity.util.Predicate;
import skadistats.clarity.util.SimpleIterator;
import skadistats.clarity.wire.common.proto.Demo;
import skadistats.clarity.wire.common.proto.NetMessages;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Provides({ UsesEntities.class, OnEntityCreated.class, OnEntityUpdated.class, OnEntityDeleted.class })
@UsesDTClasses
public class Entities {

    private final Map<Integer, BaselineEntry> baselineEntries = new HashMap<>();
    private  Entity[] entities;
    private FieldReader fieldReader;
    private EngineType engineType;

    private final FieldPath[] fieldPaths = new FieldPath[FieldReader.MAX_PROPERTIES];

    private Event<OnEntityCreated> evCreated;
    private Event<OnEntityUpdated> evUpdated;
    private Event<OnEntityDeleted> evDeleted;
    private Event<OnEntityEntered> evEntered;
    private Event<OnEntityLeft> evLeft;

    private class BaselineEntry {
        private ByteString rawBaseline;
        private Object[] baseline;
        public BaselineEntry(ByteString rawBaseline) {
            this.rawBaseline = rawBaseline;
            this.baseline = null;
        }
    }

    @Initializer(UsesEntities.class)
    public void initUsesEntities(final Context ctx, final UsagePoint<UsesEntities> usagePoint) {
        initEngineDependentFields(ctx);
    }

    @Initializer(OnEntityCreated.class)
    public void initOnEntityCreated(final Context ctx, final EventListener<OnEntityCreated> eventListener) {
        initEngineDependentFields(ctx);
        evCreated = ctx.createEvent(OnEntityCreated.class, Entity.class);
    }

    @Initializer(OnEntityUpdated.class)
    public void initOnEntityUpdated(final Context ctx, final EventListener<OnEntityUpdated> eventListener) {
        initEngineDependentFields(ctx);
        evUpdated = ctx.createEvent(OnEntityUpdated.class, Entity.class, FieldPath[].class, int.class);
    }

    @Initializer(OnEntityDeleted.class)
    public void initOnEntityDeleted(final Context ctx, final EventListener<OnEntityDeleted> eventListener) {
        initEngineDependentFields(ctx);
        evDeleted = ctx.createEvent(OnEntityDeleted.class, Entity.class);
    }

    @Initializer(OnEntityEntered.class)
    public void initOnEntityEntered(final Context ctx, final EventListener<OnEntityEntered> eventListener) {
        initEngineDependentFields(ctx);
        evEntered = ctx.createEvent(OnEntityEntered.class, Entity.class);
    }

    @Initializer(OnEntityLeft.class)
    public void initOnEntityLeft(final Context ctx, final EventListener<OnEntityLeft> eventListener) {
        initEngineDependentFields(ctx);
        evLeft = ctx.createEvent(OnEntityLeft.class, Entity.class);
    }

    private void initEngineDependentFields(Context ctx) {
        if (fieldReader == null) {
            engineType = ctx.getEngineType();
            fieldReader = ctx.getEngineType().getNewFieldReader();
            entities = new Entity[1 << engineType.getIndexBits()];
        }
    }

    @OnReset
    public void onReset(Context ctx, Demo.CDemoFullPacket packet, ResetPhase phase) {
        if (phase == ResetPhase.CLEAR) {
            baselineEntries.clear();
            for (int entityIndex = 0; entityIndex < entities.length; entityIndex++) {
                entities[entityIndex] = null;
            }
        }
    }

    @OnStringTableEntry("instancebaseline")
    public void onBaselineEntry(Context ctx, StringTable table, int index, String key, ByteString value) {
        baselineEntries.put(Integer.valueOf(key), new BaselineEntry(value));
    }

    @OnMessage(NetMessages.CSVCMsg_PacketEntities.class)
    public void onPacketEntities(Context ctx, NetMessages.CSVCMsg_PacketEntities message) {
        BitStream stream = new BitStream(message.getEntityData());
        DTClasses dtClasses = ctx.getProcessor(DTClasses.class);
        int updateCount = message.getUpdatedEntries();
        int entityIndex = -1;

        int cmd;
        DTClass cls;
        int serial;
        Object[] state;
        Entity entity;

        while (updateCount-- != 0) {
            entityIndex += stream.readUBitVar() + 1;
            cmd = stream.readUBitInt(2);
            if ((cmd & 1) == 0) {
                if ((cmd & 2) != 0) {
                    cls = dtClasses.forClassId(stream.readUBitInt(dtClasses.getClassBits()));
                    serial = stream.readUBitInt(engineType.getSerialBits());
                    if (engineType.getSerialExtraBits() != 0) {
                        // TODO: there is an extra byte encoded here for S2, figure out what it is
                        stream.skip(engineType.getSerialExtraBits());
                    }
                    state = Util.clone(getBaseline(dtClasses, cls.getClassId()));
                    fieldReader.readFields(stream, cls, fieldPaths, state, false);
                    entity = new Entity(ctx.getEngineType(), entityIndex, serial, cls, true, state);
                    entities[entityIndex] = entity;
                    if (evCreated != null) {
                        evCreated.raise(entity);
                    }
                    if (evEntered != null) {
                        evEntered.raise(entity);
                    }
                } else {
                    entity = entities[entityIndex];
                    cls = entity.getDtClass();
                    state = entity.getState();
                    int nChanged = fieldReader.readFields(stream, cls, fieldPaths, state, false);
                    if (evUpdated != null) {
                        evUpdated.raise(entity, fieldPaths, nChanged);
                    }
                    if (!entity.isActive()) {
                        entity.setActive(true);
                        if (evEntered != null) {
                            evEntered.raise(entity);
                        }
                    }
                }
            } else {
                entity = entities[entityIndex];
                if (entity.isActive()) {
                    entity.setActive(false);
                    if (evLeft != null) {
                        evLeft.raise(entity);
                    }
                }
                if ((cmd & 2) != 0) {
                    entities[entityIndex] = null;
                    if (evDeleted != null) {
                        evDeleted.raise(entity);
                    }
                }
            }
        }
        if (message.getIsDelta()) {
            while (stream.readBitFlag()) {
                entityIndex = stream.readUBitInt(engineType.getIndexBits());
                if (evDeleted != null) {
                    evDeleted.raise(entities[entityIndex]);
                }
                entities[entityIndex] = null;
            }
        }
    }

    private Object[] getBaseline(DTClasses dtClasses, int clsId) {
        BaselineEntry be = baselineEntries.get(clsId);
        if (be.baseline == null) {
            DTClass cls = dtClasses.forClassId(clsId);
            BitStream stream = new BitStream(be.rawBaseline);
            be.baseline = cls.getEmptyStateArray();
            fieldReader.readFields(stream, cls, fieldPaths, be.baseline, false);
        }
        return be.baseline;
    }

    public Entity getByIndex(int index) {
        return entities[index];
    }

    public Entity getByHandle(int handle) {
        Entity e = entities[engineType.indexForHandle(handle)];
        return e == null || e.getSerial() != engineType.serialForHandle(handle) ? null : e;
    }

    public Iterator<Entity> getAllByPredicate(final Predicate<Entity> predicate) {
        return new SimpleIterator<Entity>() {
            int i = -1;
            @Override
            public Entity readNext() {
                while(++i < entities.length) {
                    Entity e = entities[i];
                    if (e != null && predicate.apply(e)) {
                        return e;
                    }
                }
                return null;
            }
        };
    }

    public Entity getByPredicate(Predicate<Entity> predicate) {
        Iterator<Entity> iter = getAllByPredicate(predicate);
        return iter.hasNext() ? iter.next() : null;
    }

    public Iterator<Entity> getAllByDtName(final String dtClassName) {
        return getAllByPredicate(
            new Predicate<Entity>() {
                @Override
                public boolean apply(Entity e) {
                    return dtClassName.equals(e.getDtClass().getDtName());
                }
            });
    }

    public Entity getByDtName(final String dtClassName) {
        Iterator<Entity> iter = getAllByDtName(dtClassName);
        return iter.hasNext() ? iter.next() : null;
    }

}
