package mindustry.core;

import arc.*;
import arc.math.*;
import arc.util.*;
import mindustry.annotations.Annotations.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.ctype.*;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.game.Teams.*;
import mindustry.gen.*;
import mindustry.maps.*;
import mindustry.type.*;
import mindustry.type.Weather.*;
import mindustry.world.*;
import mindustry.world.blocks.*;
import mindustry.world.blocks.BuildBlock.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * Logic module.
 * Handles all logic for entities and waves.
 * Handles game state events.
 * Does not store any game state itself.
 * <p>
 * This class should <i>not</i> call any outside methods to change state of modules, but instead fire events.
 */
public class Logic implements ApplicationListener{

    public Logic(){

        Events.on(WaveEvent.class, event -> {
            if(state.isCampaign()){
                //TODO implement
                //state.getSector().updateWave(state.wave);
            }
        });

        Events.on(BlockDestroyEvent.class, event -> {
            //blocks that get broken are appended to the team's broken block queue
            Tile tile = event.tile;
            Block block = tile.block();
            //skip null entities or un-rebuildables, for obvious reasons; also skip client since they can't modify these requests
            if(tile.entity == null || !tile.block().rebuildable || net.client()) return;

            if(block instanceof BuildBlock){

                BuildEntity entity = tile.ent();

                //update block to reflect the fact that something was being constructed
                if(entity.cblock != null && entity.cblock.synthetic()){
                    block = entity.cblock;
                }else{
                    //otherwise this was a deconstruction that was interrupted, don't want to rebuild that
                    return;
                }
            }

            TeamData data = state.teams.get(tile.team());

            //remove existing blocks that have been placed here.
            //painful O(n) iteration + copy
            for(int i = 0; i < data.blocks.size; i++){
                BlockPlan b = data.blocks.get(i);
                if(b.x == tile.x && b.y == tile.y){
                    data.blocks.removeIndex(i);
                    break;
                }
            }

            data.blocks.addFirst(new BlockPlan(tile.x, tile.y, tile.rotation(), block.id, tile.entity.config()));
        });

        Events.on(BlockBuildEndEvent.class, event -> {
            if(!event.breaking){
                TeamData data = state.teams.get(event.team);
                Iterator<BlockPlan> it = data.blocks.iterator();
                while(it.hasNext()){
                    BlockPlan b = it.next();
                    Block block = content.block(b.block);
                    if(event.tile.block().bounds(event.tile.x, event.tile.y, Tmp.r1).overlaps(block.bounds(b.x, b.y, Tmp.r2))){
                        it.remove();
                    }
                }
            }
        });

        Events.on(LaunchItemEvent.class, e -> state.secinfo.handleItemExport(e.stack));

        //when loading a 'damaged' sector, propagate the damage
        Events.on(WorldLoadEvent.class, e -> {
            if(state.isCampaign() && state.rules.sector.hasWaves() && state.rules.sector.getTurnsPassed() > 0){
                SectorDamage.apply(state.rules.sector.getTurnsPassed());
                state.rules.sector.setTurnsPassed(0);
            }
        });

        //TODO dying takes up a turn (?)
        /*
        Events.on(GameOverEvent.class, e -> {
            //simulate a turn on a normal non-launch gameover
            if(state.isCampaign() && !state.launched){
                universe.runTurn();
            }
        });*/

        //disable new waves after the boss spawns
        Events.on(WaveEvent.class, e -> {
            //only works for non-attack sectors
            if(state.isCampaign() && state.boss() != null && !state.rules.attackMode){
                state.rules.waitEnemies = true;
            }
        });

    }

    /** Handles the event of content being used by either the player or some block. */
    public void handleContent(UnlockableContent content){
        if(!headless){
            data.unlockContent(content);
        }
    }

    /** Adds starting items, resets wave time, and sets state to playing. */
    public void play(){
        state.set(State.playing);
        //grace period of 2x wave time before game starts
        state.wavetime = state.rules.waveSpacing * 2;
        Events.fire(new PlayEvent());

        //add starting items
        if(!state.isCampaign()){
            for(TeamData team : state.teams.getActive()){
                if(team.hasCore()){
                    Tilec entity = team.core();
                    entity.items().clear();
                    for(ItemStack stack : state.rules.loadout){
                        entity.items().add(stack.item, stack.amount);
                    }
                }
            }
        }
    }

    public void reset(){
        State prev = state.getState();
        //recreate gamestate - sets state to menu
        state = new GameState();
        //fire change event, since it was technically changed
        Events.fire(new StateChangeEvent(prev, State.menu));

        Groups.all.clear();
        Time.clear();
        Events.fire(new ResetEvent());
    }

    public void runWave(){
        spawner.spawnEnemies();
        state.wave++;
        state.wavetime = state.hasSector() && state.getSector().isLaunchWave(state.wave) ? state.rules.waveSpacing * state.rules.launchWaveMultiplier : state.rules.waveSpacing;

        Events.fire(new WaveEvent());
    }

    private void checkGameState(){
        //campaign maps do not have a 'win' state!
        if(state.isCampaign()){
            //gameover only when cores are dead
            if(!state.rules.attackMode && state.teams.playerCores().size == 0 && !state.gameOver){
                state.gameOver = true;
                Events.fire(new GameOverEvent(state.rules.waveTeam));
            }

            //check if there are no enemy spawns
            if(state.rules.waves && spawner.countSpawns() + state.teams.cores(state.rules.waveTeam).size <= 0){
                //if yes, waves get disabled
                state.rules.waves = false;
            }

            //check if there is a boss present
            Unitc boss = state.boss();
            //if this was a boss wave and there is no boss anymore, then it's a victory
            if(boss == null && state.rules.waves && state.rules.waitEnemies){
                //the sector has been conquered - waves get disabled
                state.rules.waves = false;

                //fire capture event
                Events.fire(new SectorCaptureEvent(state.rules.sector));

                //save, just in case
                if(!headless){
                    control.saves.saveSector(state.rules.sector);
                }
            }
        }else{
            if(!state.rules.attackMode && state.teams.playerCores().size == 0 && !state.gameOver){
                state.gameOver = true;
                Events.fire(new GameOverEvent(state.rules.waveTeam));
            }else if(state.rules.attackMode){
                Team alive = null;

                for(TeamData team : state.teams.getActive()){
                    if(team.hasCore()){
                        if(alive != null){
                            return;
                        }
                        alive = team.team;
                    }
                }

                if(alive != null && !state.gameOver){
                    Events.fire(new GameOverEvent(alive));
                    state.gameOver = true;
                }
            }
        }


    }

    private void updateWeather(){

        for(WeatherEntry entry : state.rules.weather){
            //update cooldown
            entry.cooldown -= Time.delta();

            //create new event when not active
            if(entry.cooldown < 0 && !entry.weather.isActive()){
                float duration = Mathf.random(entry.minDuration, entry.maxDuration);
                entry.cooldown = duration + Mathf.random(entry.minFrequency, entry.maxFrequency);
                Call.createWeather(entry.weather, entry.intensity, duration);
            }
        }
    }

    @Remote(called = Loc.both)
    public static void launchZone(){
        if(!headless){
            ui.hudfrag.showLaunch();
        }

        //TODO core launch effect
        for(Tilec tile : state.teams.playerCores()){
            Fx.launch.at(tile);
        }

        if(state.isCampaign()){
            //TODO implement?
            //state.getSector().setLaunched();
        }

        Sector sector = state.rules.sector;

        //TODO containers must be launched too
        Time.runTask(30f, () -> {
            for(Tilec entity : state.teams.playerCores()){
                for(Item item : content.items()){
                    data.addItem(item, entity.items().get(item));
                    Events.fire(new LaunchItemEvent(new ItemStack(item, entity.items().get(item))));
                }
                entity.tile().remove();
            }
            state.launched = true;
            state.gameOver = true;

            //save over the data w/o the cores
            sector.save.save();

            //run a turn, since launching takes up a turn
            universe.runTurn();
            sector.setTurnsPassed(sector.getTurnsPassed() + 3);

            Events.fire(new LaunchEvent());
            //manually fire game over event now
            Events.fire(new GameOverEvent(state.rules.defaultTeam));
        });
    }

    @Remote(called = Loc.both)
    public static void onGameOver(Team winner){
        state.stats.wavesLasted = state.wave;
        ui.restart.show(winner);
        netClient.setQuiet();
    }

    @Override
    public void update(){
        Events.fire(Trigger.update);
        universe.updateGlobal();

        if(state.isGame()){
            if(!net.client()){
                state.enemies = Groups.unit.count(u -> u.team() == state.rules.waveTeam && u.type().isCounted);
            }

            if(!state.isPaused()){
                state.secinfo.update();

                if(state.isCampaign()){
                    universe.update();
                }
                Time.update();

                //weather is serverside
                if(!net.client()){
                    updateWeather();

                    for(TeamData data : state.teams.getActive()){
                        if(data.hasAI()){
                            data.ai.update(data);
                        }
                    }
                }

                if(state.rules.waves && state.rules.waveTimer && !state.gameOver){
                    if(!state.rules.waitEnemies || state.enemies == 0){
                        state.wavetime = Math.max(state.wavetime - Time.delta(), 0);
                    }
                }

                if(!net.client() && state.wavetime <= 0 && state.rules.waves){
                    runWave();
                }

                Groups.update();
            }

            if(!net.client() && !world.isInvalidMap() && !state.isEditor() && state.rules.canGameOver){
                checkGameState();
            }
        }
    }

}
