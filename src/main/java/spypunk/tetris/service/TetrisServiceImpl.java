/*
 * Copyright © 2016-2017 spypunk <spypunk@gmail.com>
 *
 * This work is free. You can redistribute it and/or modify it under the
 * terms of the Do What The Fuck You Want To Public License, Version 2,
 * as published by Sam Hocevar. See the COPYING file for more details.
 */

package spypunk.tetris.service;

import static spypunk.tetris.constants.TetrisConstants.HEIGHT;
import static spypunk.tetris.constants.TetrisConstants.WIDTH;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import spypunk.tetris.factory.ShapeFactory;
import spypunk.tetris.guice.TetrisModule.TetrisProvider;
import spypunk.tetris.model.Movement;
import spypunk.tetris.model.Shape;
import spypunk.tetris.model.Shape.Block;
import spypunk.tetris.model.ShapeType;
import spypunk.tetris.model.Tetris;
import spypunk.tetris.model.Tetris.State;
import spypunk.tetris.model.TetrisEvent;
import spypunk.tetris.model.TetrisInstance;

@Singleton
public class TetrisServiceImpl implements TetrisService {

    private static final int ROWS_PER_LEVEL = 10;
    private static final int LEVEL_THRESHOLD_FOR_MINIMUM_SPEED = 29;
    private static final int MINIMUM_SPEED = 1;


    private final ShapeFactory shapeFactory;

    private final Map<Integer, Integer> scorePerRows = ImmutableMap.of(1, 40, 2, 100, 3, 300, 4, 1200);

    private final Map<Integer, Integer> levelSpeeds = createLevelSpeeds();

    private final Rectangle gridRectangle = new Rectangle(0, 0, WIDTH, HEIGHT);

    private final Tetris tetris;

    @Inject
    public TetrisServiceImpl(final ShapeFactory shapeFactory, @TetrisProvider final Tetris tetris) {
        this.shapeFactory = shapeFactory;
        this.tetris = tetris;
    }

    @Override
    public void start() {
        final int speed = getLevelSpeed(0);

        tetris.setTetrisInstance(new TetrisInstance());
        tetris.setSpeed(speed);
        tetris.setNextShape(shapeFactory.createRandomShape());
        tetris.setState(State.RUNNING);

        generateNextShape();
    }

    @Override
    public void update() {
        applyGravity();
        applyMovement();
    }

    @Override
    public void pause() {
        tetris.setState(tetris.getState().onPause());
    }

    @Override
    public void move(final Movement movement) {
        if (isMovementAllowed()) {
            tetris.setMovement(Optional.of(movement));
        }
    }

    @Override
    public void hardDrop() {
        if (isMovementAllowed()) {
            tetris.setHardDropEnabled(true);
        }
    }

    @Override
    public void mute() {
        tetris.setMuted(!tetris.isMuted());
    }
 @Override
    public void returnToMenu() {
        // Criar uma nova instância do jogo efetivamente reseta tudo para o estado inicial (parado/menu)
        tetris.setTetrisInstance(new TetrisInstance());
    }
    
    private void applyGravity() {
        if (!isTetrisRunning()) {
            return;
        }

        if (!isTimeToApplyGravity()) {
            incrementGravityFrame();
            return;
        }

        if (isCurrentShapeLocked()) {
            clearCompleteRows();
            generateNextShape();
            checkShapeLocked();
        } else {
            moveShapeDown();
        }

        resetCurrentGravityFrame();
    }

    private boolean isCurrentShapeLocked() {
        return tetris.isCurrentShapeLocked();
    }

    private void incrementGravityFrame() {
        tetris.setCurrentGravityFrame(tetris.getCurrentGravityFrame() + 1);
    }

    // NOVO MÉTODO PRIVADO (Refatoração 4)
    private Optional<Movement> getEffectiveMovement() {
        return tetris.isHardDropEnabled()
                ? Optional.of(Movement.DOWN)
                : tetris.getMovement();
    }

    private void applyMovement() {
        if (!isMovementAllowed()) {
            return;
        }

        final Optional<Movement> optionalMovement = getEffectiveMovement(); // USA O NOVO MÉTODO

        if (optionalMovement.isPresent()) {
            final Movement movement = optionalMovement.get();

            tetris.setMovement(Optional.empty());

            final boolean isDownMovement = Movement.DOWN.equals(movement);

            if (isDownMovement || canShapeMove(movement)) {
                moveShape(movement);

                if (isDownMovement) {
                    updateScoreWithCompletedMovement();
                }

                if (isCurrentShapeLocked()) {
                    resetCurrentGravityFrame();
                }
            }
        }
    }

    private void addCurrentShapeBlocksToGrid() {
        tetris.getCurrentShape().getBlocks()
            .forEach(block -> tetris.getBlocks().put(block.getLocation(), block));
    }

    private void checkShapeLocked() {
        if (canShapeMove(Movement.DOWN)) {
            return;
        }

        addCurrentShapeBlocksToGrid();

        if (isGameOver()) {
            tetris.setState(State.GAME_OVER);
            tetris.getTetrisEvents().add(TetrisEvent.GAME_OVER);
        } else {
            tetris.getTetrisEvents().add(TetrisEvent.SHAPE_LOCKED);
            tetris.setCurrentShapeLocked(true);
        }

        tetris.setHardDropEnabled(false);
    }

    private void generateNextShape() {
        final Shape currentShape = tetris.getNextShape();

        tetris.setCurrentShape(currentShape);
        tetris.setCurrentShapeLocked(false);
        tetris.setNextShape(shapeFactory.createRandomShape());

        updateStatistics();
    }

    private void updateStatistics() {
        final ShapeType shapeType = tetris.getCurrentShape().getShapeType();
        final Map<ShapeType, Integer> statistics = tetris.getStatistics();
        final Integer count = statistics.get(shapeType);

        statistics.put(shapeType, count + 1);
    }

    private boolean isGameOver() {
        return tetris.getBlocks().values().stream()
                .anyMatch(block -> block.getLocation().y == 0);
    }

    private boolean isTimeToApplyGravity() {
        return tetris.getCurrentGravityFrame() == tetris.getSpeed();
    }

    private void clearCompleteRows() {
        final List<Integer> completeRows = IntStream.range(0, HEIGHT)
                .filter(this::isRowComplete).boxed().collect(Collectors.toList());

        final int completedRowsCount = completeRows.size();

        if (completedRowsCount == 0) {
            return;
        }

        completeRows.forEach(this::clearCompleteRow);

        tetris.setCompletedRows(tetris.getCompletedRows() + completedRowsCount);

        updateScoreWithCompletedRows(completedRowsCount);
        updateLevel();

        tetris.getTetrisEvents().add(TetrisEvent.ROWS_COMPLETED);
    }

    private boolean hasReachedNextLevelThreshold(int completedRows, int nextLevelCandidate) {
        return completedRows >= ROWS_PER_LEVEL * nextLevelCandidate;
    }

    private void updateLevel() {
        final int completedRows = tetris.getCompletedRows();
        final int nextLevelCandidate = tetris.getLevel() + 1;

        if (hasReachedNextLevelThreshold(completedRows, nextLevelCandidate)) {
            tetris.setLevel(nextLevelCandidate);
            tetris.setSpeed(getLevelSpeed(nextLevelCandidate));
        }
    }

    private void updateScoreWithCompletedMovement() {
        tetris.setScore(tetris.getScore() + 1);
    }

    private void updateScoreWithCompletedRows(final int completedRows) {
        final Integer rowsScore = scorePerRows.get(completedRows);
        final int score = tetris.getScore();

        tetris.setScore(score + rowsScore * (tetris.getLevel() + 1));
    }

    private void clearCompleteRow(final int row) {
        final Map<Point, Block> blocks = tetris.getBlocks();

        final List<Block> blocksToMoveDown = blocks.values().stream()
                .filter(block -> block.getLocation().y < row)
                .collect(Collectors.toList());

        IntStream.range(0, WIDTH)
                .forEach(column -> clearBlockAt(new Point(column, row)));

        blocksToMoveDown.forEach(block -> clearBlockAt(block.getLocation()));
        blocksToMoveDown.forEach(this::moveBlockDown);
    }

    private void clearBlockAt(final Point location) {
        tetris.getBlocks().remove(location);
    }

    private boolean isRowComplete(final int row) {
        return IntStream.range(0, WIDTH)
                .allMatch(column -> tetris.getBlocks().containsKey(new Point(column, row)));
    }

    private void moveShape(final Movement movement) {
        final Shape currentShape = tetris.getCurrentShape();
        final Shape newShape = movement.apply(currentShape);

        tetris.setCurrentShape(newShape);

        checkShapeLocked();
    }

    private void moveShapeDown() {
        moveShape(Movement.DOWN);
    }

    private void moveBlockDown(final Block block) {
        final Point location = block.getLocation();
        final Point newLocation = Movement.DOWN.apply(location);

        block.setLocation(newLocation);

        tetris.getBlocks().put(block.getLocation(), block);
    }

    private boolean canShapeMove(final Movement movement) {
        final Shape currentShape = tetris.getCurrentShape();
        final Shape newShape = movement.apply(currentShape);

        return newShape.getBlocks().stream().map(Block::getLocation).allMatch(this::canBlockMove);
    }

    private boolean canBlockMove(final Point location) {
        return gridRectangle.contains(location) && !tetris.getBlocks().containsKey(location);
    }

    private boolean isTetrisRunning() {
        return tetris.getState().equals(State.RUNNING);
    }

    private void resetCurrentGravityFrame() {
        tetris.setCurrentGravityFrame(0);
    }

    private static Map<Integer, Integer> createLevelSpeeds() {
        final int initialSpeed = 48;
        final Map<Integer, Integer> levelSpeeds = Maps.newHashMap();

        levelSpeeds.put(0, initialSpeed);
        levelSpeeds.put(9, 6);

        IntStream.range(1, 9).forEach(level -> levelSpeeds.put(level, initialSpeed - 5 * level));
        IntStream.range(10, 13).forEach(level -> levelSpeeds.put(level, 5));
        IntStream.range(13, 16).forEach(level -> levelSpeeds.put(level, 4));
        IntStream.range(16, 19).forEach(level -> levelSpeeds.put(level, 3));
        IntStream.range(19, LEVEL_THRESHOLD_FOR_MINIMUM_SPEED).forEach(level -> levelSpeeds.put(level, 2));


        return levelSpeeds;
    }

    private int getLevelSpeed(final int level) {
        return level < LEVEL_THRESHOLD_FOR_MINIMUM_SPEED ? levelSpeeds.get(level) : MINIMUM_SPEED;
    }

    private boolean isMovementAllowed() {
        return isTetrisRunning() && !isCurrentShapeLocked();
    }
}